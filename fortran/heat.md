# A simple heat transfer simulation #

This is a simple heat transfer simulation, that given some initial conditions, 
will model how the heat transfers across a physical space. In this case, 
we will calculate a simple 2D plate that got hot in random spots, and 
how that heat dissipates over time.

The basic intuition is or the change in heat over time is updated by 
the second-order difference of heat over space. We will use an
explicit finite differences, which parallelizes and is "functional" --
i.e., data is not mutated in place. The state, or the change in heat in at 
time step `t+1`, is computed completely from the previous time step `t` -- by 
updating the heat by the differential change in heat at small spaces. For 
a more detailed treatment, see Wikipedia or Wolfram on "heat equation" --
and further details on the numerical method can be found on the "Euler 
method" and "Jacobi method".

## parallel in Fortran ##

Using Fortran and MPI, we will compute the heat exchange in parallel
with 4 processors. The plate will be represented as a plane of points on a 
2D array. The processors, in parallel, compute the heat on their local domain, 
and in total, this calculates the change in heat across the entire plate.

The main trick is data parallelism, aka "SIMD" - Single Instruction
Multiple Data, one program applied to multiple data. Data parallelism is 
the main computational method in graphics programming and parallel programming.
For some history in vector-based data-parallel computing research, see 
[Guy Belloch's thesis](https://www.cs.cmu.edu/~guyb/papers/Ble90.pdf) and 
[Connection Machine](https://en.wikipedia.org/wiki/Connection_Machine).

Also, see his data parallel programming language 
[NESL](https://en.wikipedia.org/wiki/NESL), where many of the ideas of
this talk will be going. Things like Data Parallel Haskell (DPH), 
Thrust, Cuda, and GPU programming use many concepts from vector 
data-parallelism, and my goal is to show the overlap between it, 
relational programming, and functional combinators, and how these 
techniques can be applied. This is style is typically called 
"map-reduce" nowadays, but data parallel concepts have been around longer 
than their most recent incarnations.

## heat_transfer module ##

Fortran has `module`s, which are basically name spaces. We'll have several
`subroutine`s here, for our code.

- `initialize_grid` - set up the local domain on each of our processes
                      to set up our global computation domain; we divide
                      the problem into subproblems
- `update_step` - compute the heat transfer on our local domain; this is
                  similar to a flatmap operation
- `stencil` - compute the heat transfer in a local region; this is similar
              to the functor in a flatmap

We will try to mirror these subroutines in the other codes, such that we will
try to map the methods to similar methods, such that the numerical method
is more or less the same, but the syntax will change, and how it gets 
executed may change.

In later heat transfer examples, some will be shared memory only, i.e.,
the Thrust and Haskell versions can only be run on a single multicore machine
or GPU in the case of Thrust. This Fortran and the later Spark version
are distributed parallel, and can be run across a cluster on a network.

```fortran
module heat_transfer
  use mpi

  implicit none

contains
```

### initialize_grid ###

We'll initialize our computational grid, this is where we will store
the heat temperature values across space. We provide several arguments to it:

- `my_pid` - this processor's current id
- `x` - global x grid size
- `y` - global y grid size

It returns:

- `local_domain` - the initialized grid on this processor
- `lx` - local x grid size
- `ly` - local y grid size

```fortran
subroutine initialize_grid (my_pid, x, y, local_domain, lx, ly)
  ! parameters
  integer, intent(in) :: my_pid, x, y
  real(kind=8), allocatable, dimension(:,:,:), intent(out) :: local_domain 
  integer, intent(out) :: lx, ly
```

We'll keep it simple, just divide the computational domain `G` in four equal
portions, allocating an equal local grid to each processor so it looks like:

```
  -----      -----
  |   |      |3|2|
y |   | => y -----
  |   |      |0|1|
  -----      -----
    x          x
```

Given the size of the global grid and 4 processes, we allocate a local 
grid to each process, with halving x and y as lx and ly, to initialize the 
temperature values to 0, such that given an i, j index, we can retrieve the 
heat at a point i, j per a processor. In tandem, all 4 processes have the
temperature values that we care about, though any one process only has
a fraction (1/4th) of the data. This is the standard data-parallel technique,
as we have divided the data equally among the processes.

We allocate an "local" array of size lx, ly per processor with a "padding" of 
+2 per process in the x and y dimensions. Thus, our array bounds go from 
(0, lx+1) and (0, ly+1) rather than (1, lx) and (1, ly). This adds an 
"extra layer" of points in the physical dimensions.

The outer edge will be our "boundary condition", which will always be 0, 
such that it is always "cold" on edge of the physical domain. The other set 
of points, interior layer between processes, are for "ghost" data, which we
will talk about later. Ghost data will be unique to this Fortran version
of the heat simulation that no other version will use.

```fortran
  ! half dimensions
  lx = x / 2
  ly = y / 2
  ! allocate a grid that is lx+2 by ly+2
  ! the +2 is for the boundary and ghost layer
  allocate(local_domain(0:lx+1,0:ly+1,2))
  ! initialize the temperature to 0
  local_domain = 0

end subroutine initialize_grid
```

### update_step ###

To calculate the heat over time, we have an `update_step` that takes
the `current` time step `t` and calculates the `next` time step `t+1`.
The `current` time step is read-only, while the `next` step will be write 
only. This is a very "functional" paradigm used in our numerical method,
as state is not mutated in place, rather the previous step is immutable and 
mutability swaps between "buffers". This is how graphics programming works
and many numerical methods meant to be run on supercomputers.

```fortran
subroutine update_step(current, next, lx, ly)
  ! parameters
  real(kind=8), dimension(0:lx+1, 0:ly+1), intent(in) :: current
  real(kind=8), dimension(0:lx+1, 0:ly+1), intent(out) :: next
  integer, intent(in) :: lx, ly

  ! local values
  integer :: li, lj
```

We compute `t+1` from `t`, i.e., `next` and `current`, for all i and j, 
parallel. The update to the heat values is done by applying a "stencil" 
operation to every grid point. A stencil can be thought of as functor for a
flatmap, where we have some input, and there's a many-to-one mapping for each
point in the domain.

Thus, it is a very simple dependency graph (directed acyclic graph, or DAG):

```
-----                  -------
| t | = map stencil => | t+1 |
-----                  -------
```

The state of `t+1` only depends on the state of `t`, and it is achieved
by mapping all of `t` through the `stencil`.

Also, we limit the range of the computation from between `1` and `ly`
and `1` and `lx`, because the outer boundary is read-only data,
containing the boundary conditions and ghost data. In other versions
of the code, we will have filter functors `not_boundary` to test this
condition, but here we just have a loop that avoids this.

```fortran
  ! for all points
  do lj = 1, ly
    do li = 1, lx
      ! the heat update for a point is calculated by the stencil
      ! this is a many-to-one mapping
      call stencil(current, next, lx, ly, li, lj)
    end do
  end do
end subroutine update_step
```

### stencil ###

The stencil is our functor for a many-to-one map, that takes several points, i
and calculates a new temperature for one point.

This simple 5 point stencil computes the `next` time step's temperature
from the `current` time step via the 4 directly adjacent points in the
x and y directions. So for instance, given a temperature value at a point
in space, we will calculate its new temperature from the point above,
below, to the left, and to the right of it.

```fortran
subroutine stencil(current, next, lx, ly, li, lj)
  ! parameters
  real(kind=8), dimension(0:lx+1, 0:ly+1), intent(in) :: current
  real(kind=8), dimension(0:lx+1, 0:ly+1), intent(out) :: next
  integer, intent(in) :: li, lj, lx, ly
```

This is a simple stencil, which is a standard 5-point stencil for
finite differencing (see Wikipedia and Wolfram for a more detailed 
explanation). 

```fortran
  next(li, lj) = .5 * current(li, lj) + &
    .125 * (current(li + 1, lj) + &
            current(li - 1, lj) + &
            current(li, lj + 1) + &
            current(li, lj - 1))
end subroutine stencil
```

Though, there's a problem. What if we need data from another processor?
Recall in this simple diagram, processor 0 borders 1 and 3. What if we
need to calculate a temperature on the border between 1 or 3, but it requires
values from 1 and/or 3? For example, `i+1`, `i-1`, `j+1`, or `j-1`
may be referencing data that processor 0 doesn't have, because it was
updated on the previous step.

```
  -----      -----
  |   |      |3|2|
y | G | => y -----
  |   |      |0|1|
  -----      -----
    x          x
```

So, one solution is to send or retrieve the data as it is needed from
another processor. Another solution would use all-to-all communication 
to send the data to the processors in a join when it is needed, or like a
Spark/Hadoop, "shuffle". We won't do either one of these, but rather we
use "ghost" data -- a read-only copy of the neighboring processor's data.

### exchange_ghost ###

Rather than an all-to-all shuffle, or sending data to processors as needed,
we pre-send the data to processes who need it in one go. This is a
compromise between all-to-all and on-demand point communications.
For example, after `t+1` is computed, all processes will need a copy of 
their neighbor's data on their next update step, thus the neighbors will
send that data only do those that need it. In parallel scientific computing, 
this pre-sending of neighboring or necessary data that is needed by other 
processes, is the concept of "ghost" data (points, cells, zones, etc.) 

The reason that is it called "ghost" data, is that it is read-only. It
is a special type of immutable-data, that one processor only ever will update.
There are "owners" of a computational region, i.e. indexed data, 
and new values are only computed by the "owner". Any updates to those values
will be sent from the owner. Ghost data are somewhat similar to Spark 
shared variables, but different in that this shared data are not 
replicated/broadcast to all processes. 

```fortran
subroutine exchange_ghost (my_pid, local_domain, lx, ly)
  ! parameters
  integer, intent(in) :: my_pid, lx, ly
  real(kind=8), dimension(0:lx+1,0:ly+1), intent(inout) :: local_domain

  ! local variables
  integer :: stat(MPI_STATUS_SIZE)
  integer :: ierror
```

Rather, ghost data are only shared with the processes that 
explicitly need it. Ghost data is an optimization for communication, 
as it is the most expensive part of (most) parallel computations, as
it is a compromise between all-to-all and on demand point-to-point: it is
a repartition doesn't move data, rather it creates read-only copies of
data to those that need it.

In our following ghost exchange, we share data with our immediate
neighbors on their boundaries the physical domain: process 0 shares with i
(1, 3), process 1 shares with (0, 2), process 2 shares with (1, 3), and 
process 3 shares with (0, 2). 

```
 3 <-> 2
 ^     ^
 |     |
 v     v
 0 <-> 1
```

We use MPI (Message Passing Interface) for message passing, typically used
in scientific computing with supercomputers over a high-speed interconnects,
like Infiniband. Even with this simple computation, it's quite a bit of 
code that is prone to errors.  Later, we will talk talk about the relationship 
of this to higher-order combinators, and how this complex implementation 
can be abstracted away with a "join".

```fortran
  ! 0 shares with 1 and 3
  if (my_pid == 0) then
    call mpi_send &
      (local_domain(1:lx,ly), lx, MPI_REAL8, 3, 0, MPI_COMM_WORLD, ierror)
    call mpi_send &
      (local_domain(lx,1:ly), ly, MPI_REAL8, 1, 0, MPI_COMM_WORLD, ierror)
    call mpi_recv &
      (local_domain(1:lx,ly+1), lx, MPI_REAL8, 3, 0, MPI_COMM_WORLD, stat, &
       ierror)
    call mpi_recv &
      (local_domain(lx+1,1:ly), ly, MPI_REAL8, 1, 0, MPI_COMM_WORLD, stat, &
       ierror)
  ! 1 shares with 0 and 2
  else if (my_pid == 1) then
    call mpi_send &
      (local_domain(1:lx,ly), lx, MPI_REAL8, 2, 0, MPI_COMM_WORLD, ierror)
    call mpi_send &
      (local_domain(1,1:ly), ly, MPI_REAL8, 0, 0, MPI_COMM_WORLD, ierror)
    call mpi_recv &
      (local_domain(1:lx,ly+1), lx, MPI_REAL8, 2, 0, MPI_COMM_WORLD, stat, &
       ierror)
    call mpi_recv &
      (local_domain(0,1:ly), ly, MPI_REAL8, 0, 0, MPI_COMM_WORLD, stat, &
       ierror)
  ! 2 shares with 1 and 3
  else if (my_pid == 2) then
    call mpi_send &
      (local_domain(1:lx,1), lx, MPI_REAL8, 1, 0, MPI_COMM_WORLD, ierror)
    call mpi_send &
      (local_domain(1,1:ly), ly, MPI_REAL8, 3, 0, MPI_COMM_WORLD, ierror)
    call mpi_recv &
      (local_domain(1:lx,0), lx, MPI_REAL8, 1, 0, MPI_COMM_WORLD, stat, &
       ierror)
    call mpi_recv &
      (local_domain(0,1:ly), ly, MPI_REAL8, 3, 0, MPI_COMM_WORLD, stat, &
       ierror)
  ! 3 shares with 0 and 2
  else
    call mpi_send &
      (local_domain(1:lx,1), lx, MPI_REAL8, 0, 0, MPI_COMM_WORLD, ierror)
    call mpi_send &
      (local_domain(lx,1:ly), ly, MPI_REAL8, 2, 0, MPI_COMM_WORLD, ierror)
    call mpi_recv &
      (local_domain(1:lx,0), lx, MPI_REAL8, 0, 0, MPI_COMM_WORLD, stat, &
       ierror)
    call mpi_recv &
      (local_domain(lx+1,1:ly), ly, MPI_REAL8, 2, 0, MPI_COMM_WORLD, stat, &
       ierror)
  endif

end subroutine exchange_ghost
```

### write_output ###

Here we have our write routine. I won't go into detail on this, but
it just computes the filename for every time step `t` with processor
`p`, and writes the data in Fortran formatted ASCII. This includes
the i, j, and temperature at every point.

```fortran
subroutine output_data(step, lx, ly, pid, t)
  ! arguments
  real(kind=8), dimension(0:lx+1, 0:ly+1), intent(in) :: step
  integer, intent(in) :: pid, lx, ly, t

  ! local variables
  character(len=80) :: filename, time
  integer :: i, j, offset_i, offset_j

  ! compute filename
  write(filename,'(A12,I1,A1)') 'data/output.', pid, '.'
  write(time, '(I5)') t

  ! compute offsets for i and j by processor
  if (pid == 0 .or. pid == 3) then
    offset_i = 0
  else
    offset_i = 100
  end if
  if (pid == 0 .or. pid == 1) then
    offset_j = 0
  else
    offset_j = 100
  end if

  ! write all of the data to the file
  open(1000, file=trim(filename) // adjustl(time))
  do j = 1, ly
    do i = 1, lx
      write(1000,'(I5, I5, ES27.17E3)') i + offset_i, j + offset_j, step(i, j)
    end do
  end do
  close(1000)
end subroutine output_data
```

### add_random_heat ###

This subroutine adds a random amount of heat on the local domain,
thus it will add 4 random spots of heat.

```fortran
subroutine add_random_heat(local_domain, lx, ly)
  ! parameters
  real(kind=8), dimension(0:lx+1, 0:ly+1), intent(inout) :: local_domain
  integer, intent(in) :: lx, ly

  ! local variables
  real(kind=8) :: f, g, heat
  integer :: i, j

  ! add heat to a random position
  call random_number(f)
  call random_number(g)
  i = floor(f * lx) + 1
  j = floor(g * ly) + 1

  local_domain(i, j) = local_domain(i, j) + 1
end subroutine add_random_heat
```

End of `heat_transfer` module.

```fortran
end module heat_transfer
```

## heat program ##

This is our main driver for the heat transfer. We iteratively calculate
the `next` time step's heat from the current one, in a "double buffering"
fashion, i.e., the `current` state is immutable and the `next` state is
calculated from it.

On every iteration, we will:

- given the `current` time step
  - exchange ghost data to neighbors
  - update the `next` time step using the `current`
  - swap the buffers that represent the two time steps (flip mutability)

Below, `t` and `t+1` is represented by the 3D array `local_domain`.
An outer controlling loop will swap between `t` and `t+1` states, and the
"double-buffering" strategy that is used by most data parallel computations, 
such that the previous state is read-only, and the next state is write-only.

```fortran
program heat
  use mpi
  use heat_transfer

  ! initialization
  integer, parameter :: x = 200
  integer, parameter :: y = 200

  ! current and next buffers
  real(kind=8), allocatable, dimension(:,:,:) :: local_domain

  ! local variables
  integer :: my_pid, num_processors, lx, ly
  integer :: ierror
  integer :: t, t_next, ssize
  integer, dimension(:), allocatable :: seed
```

Here, we initialize MPI. We get our process id `my_pid` and the
number of processors in the computation domain.

```fortran
  call mpi_init(ierror)
  call mpi_comm_rank(MPI_COMM_WORLD, my_pid, ierror)
  call mpi_comm_size(MPI_COMM_WORLD, num_processors, ierror)

  if (num_processors /= 4) then
    print *, 'ERROR: only works with 4 processors!'
  else
```

Initialize the `local_domain` using the parameters for the global grid,
x and y. This returns our initialized grid and the size of it in lx and ly.
We also add 4 random spots of heat, by adding one per process on
`local_domain`.

```fortran
    call initialize_grid(my_pid, x, y, local_domain, lx, ly)
    call random_seed(size=ssize)
    allocate(seed(ssize))
    seed = my_pid
    call random_seed(put=seed)
    deallocate(seed)
    call add_random_heat(local_domain(:,:,2), lx, ly)
    call output_data(local_domain(:,:,2), lx, ly, my_pid, 0)
```

Our main loop, where we have source data (`current` time step), which is 
considered read-only, and the sink data (`next` time step), which is the 
write target.  The two buffers are accessed by indexing the last dimension 
(in Fortran, the last dimension "runs" the slowest, unlike C).

We iterate for 10000 time steps, outputting data every 100th. 

```fortran
    ! for time steps
    do t = 1, 10000
      ! exchange ghost data 
      call exchange_ghost(my_pid, local_domain(:,:,modulo(t, 2)+1), lx, ly)
      ! compute next from current
      call update_step(local_domain(:,:,modulo(t,2)+1), &
                       local_domain(:,:,modulo(t+1,2)+1), lx, ly)
      ! output data every 100
      if (modulo(t,100) == 0) then
        call output_data(local_domain(:,:,modulo(t+1,2)+1), lx, ly, my_pid, t)
      end if
    end do
    deallocate(local_domain)
  endif

  call mpi_finalize(ierror)
end program heat
```

The other versions of this code, Haskell, Thrust, and Spark, all apply
data parallel computations to solve this explicit heat transfer equation.
They will all look similar to this and I will point out the differences
and similarities as they arise.

The main benefit that the Fortran version has is that it is blazing
fast. It takes 3s to do 10000 iterations on a 200x200 grid with 4
processors. The Thrust version takes 4.5 seconds, the Haskell version
takes 60 seconds, and the Spark version takes over 30 minutes.
