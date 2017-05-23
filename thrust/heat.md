# heat in Thrust #

Thrust is a template system for C++ that provides vector data-parallel 
(flattened parallelism) for both multi-core CPUs and GPUs. Thus, 
this same program can be run on a GPU by changing the executor engine.

Similar to Haskell, this version is shared-memory only, and will only
work on a multi-core computer or a GPU, rather than a cluster, like
the Fortran version. Also, Thrust is similar to the `accelerate` package,
as it can compile to different "backends", like multi-core CPU and GPU.
So, we don't have to worry about partitioning the data per processor.

The main difference between this version and the Haskell version is now
we don't have multi-dimensional arrays. Rather, we primarily have single
vectors and iterate over those in parallel. There are "zip iterators"
that allow us to bind multiple vectors (columns) together into a logical
view. A multi-dimensional array is represented by creating unique ids 
(keys) for a position on a grid, i.e., mapping i and j to some index.

At this point, the data representation starts to look like a relational table.
If we consider each vector to be a column, and the index into a value
on the column to be the primary key, then the data model more-or-less
is a columnar database. 

As before, this version still has, like Fortran and Haskell:

- `initialize_grid` - given a vector that represents the temperature will
                      initialize the values
- `stencil` - here, the stencil takes a "zip iterator", which is multiple
              1D arrays that represent the data in the 2D grid - this one
              is the one that looks the most different from Haskell and Fortran
- `update_step` - similar to the others where it is applying a functor to
                  the grid, but has to construct the zip iterator for the
                  `stenctil`

```cpp
#include <thrust/system/omp/execution_policy.h>
#include <algorithm>
#include <iostream>
#include <vector>
#include <fstream>
#include <sstream>
```

### initialize_grid ###

In this version, the grid is initialized with the static values of the 
dimensions of 2D grid, though it is creating a 1D array. We will
discuss how to index into the 1D array to retrieve the value at a 2D
position, i, j, in the next function `not_boundary`.

```cpp
template <typename T, size_t X, size_t Y>
void initialize_grid(T* current, T* next)
{
  current->resize(X * Y, 0.0);
  next->resize(X * Y, 0.0);
}
```

### not_boundary ###

`not_boundary` tests the condition that we aren't on the boundary, and
show how we create an index (key) for a position in the 2D grid. 

The relationship is `index = i + j * X`, which is a the common way for
creating an index for a 2D data set, as this is how C and Fortran represent
multidimensional arrays in memory. For example in a 2D array in C,
if you index into `A[i][j]`, it is really translating the address into
`j + i * J`, where `J` is the size of the j dimension. 

Thus below, the inverse key function, is `i = index % X` and `j = index / X`,
to determine if a position i, j is not in the boundary. In 
flattened/vector data parallelism, this is quite common, as you have to have
an indexing structure to map from array indices to logical indices,
like 2D array grids.

```cpp
template <size_t X, size_t Y>
struct not_boundary : 
  thrust::unary_function<size_t, bool> 
{
  bool operator() (size_t index) const 
  {
    size_t i = index % X; // our inverse keying function
    size_t j = index / X; // we index into data as "index = i + j * X"
    return !((i == 0 || i == (X - 1)) ||
             (j == 0 || j == (Y - 1)));
  }
};
```

### add_random_heat ###

This is similar to other initializations, except here we do it serially
like Haskell, rather than in parallel like Fortran. In Haskell, we
created the indices and did a transform over the data testing if
the index was in the boundary. Here, we generate a random index from
the 1D size of the array and test if it is in the logical 2D bounds.

```cpp
template <typename T, size_t X, size_t Y>
void add_random_heat(T* grid)
{
  for (int8_t i = 0; i < 4; i++)
  {
    size_t index = rand() % grid->size();
    // I'm lazy and have a break out of an infinite loop because
    // I don't like having tests twice or sentinel variables for while loops
    while(1)
    {
      size_t index = rand() % grid->size();
      // add heat if it is not 1.0 already and exit
      if ((*grid)[index] == 0.0 && not_boundary<X, Y>()(index))
      {
        (*grid)[index] = 1.0;
        break;
      }
    }
  }
}
```

### stencil ###

In this version of the stencil operator, it takes a tuple which is
the temperature at the center position and the temperature in the i+1, i-1,
j+1, and j-1 directions in the 2D grid from that center. It is a tuple 
rather than the array itself is that the "offsets" are precalculated 
before the map. 

The tuple can be thought of as a row in a table of data, 
`(center REAL, left REAL, right REAL, bottom REAL, top REAL)`.

It would have been possible to pass a single array representing the 2D
grid as the input, and then manually calculate the indexing function 
to calculate the offset indices. For example to calculate the index in the
i + 1 direction, it would be `index' = (index % X + 1) + (index / X) * X`.

In the next function, `update_step` we are able to show how the tuple is
created.

```cpp
template<typename T>
struct stencil : 
  thrust::unary_function<thrust::tuple<T, T, T, T, T>, T>
{
  T operator() (thrust::tuple<T, T, T, T, T> t) const 
  {
    return 0.125 * (thrust::get<1>(t) + thrust::get<2>(t) +
                    thrust::get<3>(t) + thrust::get<4>(t)) +
           0.5 * thrust::get<0>(t);
  }
};
```

### update_step ###

`update_step` is a "one-liner" like Haskell (albeit a VERY verbose one-liner),
where `transform_if` is a combination map and filter. It takes an input
vector `current` time step, and generates the `next` time step by mapping
`stencil` over all elements in `current` that are `not_boundary`.
Though, it's not entirely true that it is just the `current` time step,
but it is that, and 4 references joined together with a zip.

As mentioned in the previous section, the input passed to the `stencil`
is actually a tuple. The tuple is an element from a zip of the temperature 
and the temperature in the i+1, i-1, j+1, j-1 directions. We are
playing a trick here that we have a definite structured mapping of 
i, j to array index and vice versa, and can supply iterators starting
at those positions. That is, j+1 is +X indices, so we can pass an iterator
starting at +X -- that said, we have 4 columns that now represent the
temperature in the offset directions from a center position.

By zipping our columns (arrays) together, now, our data is 
can be thought of as a relational table. We have taken a set of columns,
the temperature values, and zipped them together into a table. The
primary key on the rows are the array indices, and iterating over table
returns rows (tuples).

```cpp
template <typename T, typename V, size_t X, size_t Y>
void update_step(T* current, T* next) {
  thrust::transform_if( // map + filter over the data
```

Here is actually where we operate in parallel, `thrust::omp::par`, which
tells Thrust to run on the OpenMP backend, i.e., multi-core CPU. We could
also swap it out for GPU semantics and have it automatically run in a GPGPU
style without any other changes. This is where vector data-parallelism shines
is that its programming model maps both equally well to multi-core CPU
and GPU by using arrays and transforms over arrays.

Like `Repa`, we apply a `computeP`, or in `accelerate` you would specify a 
backend over a data transform.

```cpp
    thrust::omp::par, // run in parallel on the CPU using OpenMP
    thrust::make_zip_iterator(
      thrust::make_tuple(current->begin(),        // input start
                         current->begin() - 1,    // temperature i-1, j
                         current->begin() + 1,    // temperature i+1, j
                         current->begin() - X,    // temperature i, j-1
                         current->begin() + X)),  // temperature i, j+1
    thrust::make_zip_iterator(                    // input end
      thrust::make_tuple(current->end(),
                         current->end() - 1,
                         current->end() + 1,
                         current->end() - X,
                         current->end() + X)),
```

One we have to supply, which was implicit in the Haskell version through 
`traverse`, is how to iterate over the input. In Fortran, it was a doubly-
nested for loop, while here since we have flat vectors, we supply an
iterator starting at 0 and counting up -- or basically, iterate over the
"primary key", the array indices starting at 0.

In other scenarios where the mapping from 1D to 2D is not so structured, like
arbitrary unstructured grids, we would have to supply an "indexing" vector
to map into other vectors. In vector data parallelism, there are mechanisms i
for allowing one vector to serve as an index into another vector, to be
able to do "segmented" operations. We won't go into detail on these
segmented operations, but in essence, we can think of passing indices
(like an index in a relational database) as input, and use them to allow
functions to be able to lookup column values.

```cpp
    thrust::make_counting_iterator<size_t>(0),    // how to iterate over input
    next->begin(), // output
    stencil<V>(), // map functor
    not_boundary<X, Y>() // filter functor
  );
}
```

### string_transform ###

This is an auxiliary function used by `write_output`, similar to the
`format` function that is used in the Haskell version. We transform
an index into its i, j representation, along with its temperature,
into a string.

```cpp
template <typename V, size_t X, size_t Y>
struct string_transform :
  thrust::unary_function
    <thrust::tuple<size_t, V>, std::string>
{
  std::string operator() (thrust::tuple<size_t, V> t) const
  {
    if (not_boundary<X, Y>()(thrust::get<0>(t))) // if not boundary
    {
      std::stringstream ss;
      ss << thrust::get<0>(t) % X << " " // i position of index
         << thrust::get<0>(t) / X << " " // j position of index
         << thrust::get<1>(t) << "\n";   // temperature
      return ss.str();
    }
    else
    {
      return "";
    }
  }
};
```

### write_output ###

This output function is similar to the Haskell version in that is a map
of a value to string functor over the grid into a file. The transformer will
take the array index and temperature to turn it into the same format as the
others. Here, we use `std::transform` since we have to serialize to
output and can't run in parallel (`std` doesn't have `transform_if`
so we put the `not_boundary` condition inside the functor).

```cpp
template <typename T, typename V, size_t X, size_t Y>
void write_output(T* grid, size_t t)
{
  std::ofstream file;
  std::stringstream filename;
  filename << "data/output.0." << t;

  file.open(filename.str());

  // map indices and temperatures into string to a file
  std::transform(thrust::make_zip_iterator(  // start iterator 
                  thrust::make_tuple(
                   thrust::make_counting_iterator<size_t>(0), // indices
                   grid->begin())),                           // temperatures
                 thrust::make_zip_iterator(  // end iterator
                  thrust::make_tuple(
                   thrust::make_counting_iterator<size_t>(grid->size()),
                   grid->begin())),
                 std::ostream_iterator<std::string>(file),
                 string_transform<V, X, Y>()); // functor

  file.close();
}
```

## heat program ##

Here, we can see that our temperature grid type, `vector` is explicitly
a 1D vector from `std::vector`. All the other elements of the 2D temperature,
e.g., i and j, are implicit and calculated from the array indices themselves.
This was shown earlier in `not_boundary` function for example.

```cpp
typedef double float64_t;
typedef typename std::vector< float64_t > vector;

static const size_t x = 202;
static const size_t y = 202;
```

Here, the `main` program loop looks closer to Fortran, than to Haskell,
because of needing to explicitly provide two buffers, `current` and `next`.
At the end of each iteration, we swap between the two, such that the output
becomes the input for the next step, and vice versa.

```cpp
int main(void) {
  vector t0, t1;                  // t and t+1 buffers
  vector *current, *next, *swap;  // reference swaps
  current = &t0;
  next = &t1;

  // initial condition
  initialize_grid<vector, x, y>(current, next); 
  add_random_heat<vector, x, y>(current);
  write_output<vector, float64_t, x, y>(current, 0);

  // iterate until done
  for (size_t t = 1; t <= 10000; t++)
  {
    // update
    update_step<vector, float64_t, x, y>(current, next);

    // write
    if (t % 100 == 0)
    {
      write_output<vector, float64_t, x, y>(next, t);
    }

    // swap
    swap = current;
    current = next;
    next = swap;
  }

  return 0;
}
```

This version shows the benefit of a data parallel model for being able
to leverage multi-core CPU and GPUs. The same code can be compiled and run
on both systems by just changing one line of code to tell the system how
you want to execute it. `accelerate` for Haskell has similar properties,
as it provides both a multi-core CPU backend and an OpenCL backend.

Also, we start seeing the parallels between the functional combinator
model (map-reduce-filter), the data parallel model, and the relational
data model. In our next example, Scala + Spark, we will remove array
indexing and explicitly go towards a relational "join" computation.
