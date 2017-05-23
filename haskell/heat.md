# heat in Haskell #

For our data parallelism, we will use `Repa`, which has built on concepts
from Data Parallel Haskell, which itself was inspired by NESL from Blelloch.
There are other choices within Haskell itself, such as `accelerate`, and
I chose `Repa` due to having an easier time understanding its documentation. 
So, there isn't a preference over `accelerate` other than I was able to figure
out `Repa` faster personally.

One of the significant differences between the Fortran version and this
is, that the Haskell (and the Thrust version as well) is shared-memory only, 
i.e., will only work on a multi-core computer, rather than a cluster 
or supercomputer -- but it is still parallel computation.

In particular, this means that we don't have to worry about partitioning the 
data to multiple "nodes" (computers), or "ghost" cells like in Fortran, because 
all processes have access to all data elements in the domain. One notable
thing is that supercomputing has an alternative to MPI, which is called PGAS,
Parallel Global Address Space. This allows memory and programming to be 
under a unified address space, hiding some of the complexity of explicit 
message passing like MPI does. Though, still requires coordination and
optimization like NUMA (non-uniform memory addressing) does.

This version still has, like the Fortran:

- `initialize_grid` - differs from Fortran in that the data is automatically
                      segmented per process
- `update_step` - same as Fortran, maps a functor over all data
- `stencil` - more or less the same as Fortran, the functor

```haskell
{-# LANGUAGE ScopedTypeVariables #-}

module Main where

import Data.Array.Repa as R hiding ((++), map, zip)
import System.Random (randomR, mkStdGen)
```

### initialize_grid ###

In `Repa`, we will be using multidimensional arrays, in this case a
2D array of i, j, dimensions of  `(Z :. x :. y)`, to temperature value. We 
initialize the temperature to 0.0.

```haskell
x = 202 :: Int
y = 202 :: Int

is = [0..x-1]
js = [0..y-1]

grid = [ (i, j) | i <- is, j <- js ] -- this is for output later

initialize_grid = fromListUnboxed (Z :. x :. y) $ -- create 0s over the grid
  map (\_ -> 0 :: Double) grid
```

### add_random_heat ###

In this version, we calculate a list of (i, j) that we want to add
random heat to. Except we have access to all of the data, and it is 
done in serial. So that the random i, j are generated over the entire 
global range of x and y, rather than one per process.

```haskell
add_random_heat_gen 1 rx ry g = -- base case of 1
  let (x, g') = randomR rx g
      (y, g'') = randomR ry g' in ([(x, y)], g'')
add_random_heat_gen n rx ry g = -- recursive case
  let (xy, g') = add_random_heat_gen (n-1) rx ry g in go xy g'
  where 
    go xy g_n = 
      let ([e], g_n') = add_random_heat_gen 1 rx ry g_n in
        if elem e xy then
          go xy g_n' 
        else
          (e : xy, g_n')
-- create 4 random i, j
add_random_heat = fst $ add_random_heat_gen 4 (1, x-2) (1, y-2) (mkStdGen 42)
```

### not_boundary ###

Just a test to know if we are in the computation bounds. In the Fortran
version, we have a loop over i, j that only goes to the bounds. In
this version, since we use combinators that map over all values, we
have to test or filter out values that are not the boundaries.

```haskell
not_boundary i j = i > 0 && i < x-1 && j > 0 && j < y-1
```

### stencil ###

This is our functor, the 5-point finite difference kernel, that
is similar to the Fortran version. We index in one step in each direction
for the center value and update it by the neighbor values. We
also use `not_boundary` as a inner filter on the functor.

In the Thrust and Spark versions of the code, we will get away from
using a 2D array (Thrust) and then get away from using arrays altogether
(Spark). Thus, the `stencil` is going to look different.

We don't use a `filter` then `map` type paradigm, due to `Repa.traverse`,
which is used in the `update_step` function.

```haskell
stencil f (Z :. i :. j) = 
  if not_boundary i j
  then -- only calculate heat if it's within the boundary 
    0.125 * (f (Z :. i+1 :. j) + f (Z :. i :. j+1) + 
             f (Z :. i-1 :. j) + f (Z :. i :. j-1)) + 
      0.5 * f (Z :. i :. j)
  else 
    0
```

### update_step ###

`update_step` becomes a simple one liner due to `computeP $ R.traverse`,
which is a parallel map over the temperature array using `stencil`.
In Fortran, this was `do j = 1, y - 1, do i = 1 x -1` over `current`.

`id` says that we want to return the same "shape" as the incoming
array `current`, so that the output has the same dimensions as the input.
`computeP` causes `traverse` to be executed in parallel, iterating over all
values in the array. This is the main reason `not_boundary` is in the 
`stencil`, because the input, `current`, has the same dimensions as the output,
and thus can't use a `filter $ map` paradigm.

```haskell
-- our main comptation, traverse the grid applying the stencil in parallel
update_step current = computeP $ R.traverse current id stencil
```

### write_output ###

If we were to rewrite the output function in a forward-style, pipe way:
`toList | zip grid | filter not_boundary | map format | foldr ++ | writeFile`

```haskell
format ((i, j), t) = (show i) ++ " " ++ (show j) ++ " " ++ (show t) ++ "\n"

write_output ts t = writeFile ("data/output.0." ++ (show t)) $ 
  foldr (++) "" $ map format $ 
  filter (\((i, j), _) -> not_boundary i j) $
  zip grid $ toList ts
```

## heat program ##

The initial temperature `temp` takes the array of 0s and turns it into
a 1 if the i, j is in the `add_random_heat` list.

```haskell
-- initial condition of 0s except at 4 random points
temp = computeP $ R.traverse initialize_grid id 
  (\_ (Z :. i :. j) -> 
    if elem (i, j) add_random_heat then 1 :: Double else 0 :: Double)
```

The `main` program and `loop` is similar to the Fortran version, except
we don't have to explicitly have two buffers, `t` and `t+1`. Due to referential
transparency (immutability), `next` is calculated from `update_step current`.
The garbage collector makes sure that the old data is freed up as
necessary, since it goes out of scope due to tail recursion. Thus,
it is very clear that the dependencies are `t+1` depends on `t`, or
`next` depends on `current`.

```haskell
loop t current = do
  -- next time step is the update of the current
  next :: Array U DIM2 Double <- update_step current
  if t `mod` 100 == 0
    then write_output current t
    else return ()
  -- stop once we reach 10000
  if t < 10000
    then loop (t+1) next
    else return ()

main :: IO ()
main = do
  current :: Array U DIM2 Double <- temp
  loop 0 current
```

The Haskell version shows the immediate benefit of not needing `for` loops
by using `traverse`, as in data parallelism, we are applying a map over
all data elements. We use implicit double buffering, or in this case, the
`next` time step is only dependent on the `current`, i.e., 
`next <- update_step current` , we are able to succinctly describe the 
data dependencies.

This is why functional programming in the style of immutability works 
so well many data parallelism problems. There is a batch of work 
that is completely independent of mutual dependencies, since we have
reduced the computation to `new state = old state + computation`. In a lot
of ways, this is how the main loop in Elm works, the main loop in graphics
programming works, and a whole host of other data parallel programming 
problems.

