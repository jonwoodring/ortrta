# heat in Spark #

Most people here I suspect already know Apache Spark, but for those that don't
it is a parallel computational system that started with Hadoop and
encapsulates the ideas of "map-reduce" parallel programming, i.e.,
data parallelism. It works in the Apache Java ecosystem and has several
front-ends for other languages such as Python and R. Python has `dask`
now, that is very similar to Spark in terms of execution model, but is
more array and data frame oriented. I actually prefer Apache Flink over
Spark, because it is more relational oriented, but I am showing Spark because
it is more familiar.

Up to this point, likely, if you are familiar with numerical methods and
scientific computing, the Haskell and Thrust versions *probably* didn't
seem too crazy. The use of `transform` and `map` isn't too weird to implement
the types of operations that you are accustomed to.

Here's where it gets crazy: **We won't use arrays and array indexing anymore**.
At this point, especially if you come from a scientific computing background,
you're probably thinking "no arrays? that's crazy talk! all of my computations
are built around matrices and arrays". Just look at Matlab, Fortran,
Python Numpy, Julia, etc.

In particular, consider this simple scenario of a 3x3 array. Up til now 
our stencil was given a center value at i, j, where we have i = 1 and j = 1,
we calculate the temperature from its neighbors. For the value at i, j, we 
use array indexing, i+1, i-1, j+1, and j-1, to get all of the values. Then 
the new value at i,j is the weighted sum of all of these values.

```
  -------------------
  |     |     |     |
2 |     |i,j+1|     |
  |     |     |     |
  -------------------
  |     |     |     |
1 |i-1,j| i,j |i+1,j|
  |     |     |     |
  -------------------
  |     |     |     |
0 |     |i,j-1|     |
  |     |     |     |
  -------------------
     0     1     2
```

What I remove array indexing and told you you can't directly compute
neighbors from indices? Instead, we'll have a unique label for each 
point in the grid, and you CAN look up the temperature for the labels.

What is our stencil now? Well, for C, it's the weighed sum of the
values at C, A, D, B, and E. But, how do we "calculate" A, D, B and E given C,
i.e., how do we know there's a relationship between C and its neighbors?

Before, we could take i,j and add or subtract 1 in each direction,
and then use array indexing to get the values.

```
  -------------------
  |     |     |     |
  |     |  A  |     |
  |     |     |     |
  -------------------
  |     |     |     |
  |  B  |  C  |  D  |
  |     |     |     |
  -------------------
  |     |     |     |
  |     |  E  |     |
  |     |     |     |
  -------------------

```

With a explicit relationships! We'll store adjacency information in a table.

Think about how we use arrays: they are maps (maps in the sense of
key-value pairs) from i, j to temperature, like heat[i,j] = temperature. 
In the Thrust version, we removed i, j and had to have a keying function 
from a 1D index to a 2D index and vice versa, like heat[key(i,j)] = temperature.

Now we have a function or map, `topology` that given a key, like C,
will return the list of neighbors, `topology[C] = [A, B, D, E]`.
To represent this, we'll use a relational table, i.e., a multiset of tuples
with named attributes.

Relational tables can be thought of describing attributes, via named tuples,
and their relationships: a more "generalized" key-value store, such that
any column can serve as a key. That is, a value can be a key and a key
can be a value, there isn't a distinction between the two, other than
there may be indices that accelerate lookup (like in dictionary or hash
map structures).

(Also, there are disputes between relational algebra formalism and
 how relational tables are used in practice, aka SQL and databases. I err 
 on the side of practice and thus some things I may say about the
 relational data model not be "formally correct" in the relational algebra 
 sense.)

```
   topology table    
---------------------
| center | neighbor |
---------------------
|   C    |    A     |
|   C    |    B     |
|   C    |    D     |
|   C    |    E     |
---------------------
                    
```

How do we get the temperatures?  With more relationships/functions/maps!
`temperature[key]` and `position[key]` which can be represented by more tables.
Given a unique `key`, we can look up all of its neighbors, its position,
and their temperatures.

Thus, to go from an array model, we can make all of the implicit relationships 
found in array-based computation to explicit relationships in a relational
computation.

Actually, this isn't as crazy as it seems, because for numerical methods
with "unstructured grids", i.e., arbitrary geometry, it is necessary
to have explicit relationships between elements. Thus, in some sense, we
are using the more generalized computational model, and drawing out its
implications in data parallel, functional, and relational programming.

```
   topology table         temperature table     position table
--------------------- ----------------------- ------------------
| center | neighbor | | center | heat value | | center | i | j |
--------------------- ----------------------- ------------------
|   C    |    A     | |   C    |    0.0     | |   C    | 1 | 1 |
|   C    |    B     | |   A    |    0.0     | |   A    | 1 | 2 |
|   C    |    D     | |   B    |    0.0     | |   B    | 0 | 1 |
|   C    |    E     | |   D    |    0.0     | |   D    | 2 | 1 |
--------------------- |   E    |    0.0     | |   E    | 1 | 0 |
                      ----------------------- ------------------
```

At this point, you're probably wondering, well how do we compute everything?
It's going to be done through a combination of map-filter-reduce 
with "join" and "group by". We've seen join in the SQLite analysis, and it is 
one of the keystones of the relational data model. Group by or "windowing"
is found commonly in both the relational model and the vector data-parallel 
model (where in vector data-parallelism they are known by segmented 
operations).

### start of the Scala + Spark program ###

Spark set up, nothing particularly interesting here, other than Spark
is distributed, like Fortran + MPI, and can run on a cluster of computers
over a network aka interconnect. The big difference between Spark and MPI 
is that the communication between computers is mostly abstracted away in 
Spark, except for the partitioning function.

Whereas MPI will use both point-to-point communication and collectives
(we did not use any collectives in our earlier Fortran example), Spark
only uses collective operations, such as groupByKey, reduceByKey, join, etc.

What's different now than before is that we don't have an `initialize_grid`
that initializes the array. Rather, we have three separate initialization
routines: `grid`, `topo` and `temp` that serve in its place. Also, `stencil`
does not exist anymore, because the `stencil` computation is encoded in
`topo` and `update_step`. `update_step` is now a combiner chain, transforming
the data with a series of functions.

- `initialize_grid` is gone, which created an array of temperatures, and is:
  - `grid` - computes the relationship between keys and i,j
  - `topo` - computes the relationship between keys and neighbors, encodes
             what was previously in `stencil`
  - `temp` - computes the relationship between keys and temperature
- `stencil` is gone, and is replaced by `topo` and `update_step`
- `update_step` - similar to past `update_steps` except that the 
                  `stencil` computation is folded in by relationships on
                  `topo` rather than a functor - and it is a combiner chain

```scala
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext, Partitioner}
import org.apache.log4j.{Level, Logger} 

object heat {

val x = 202;
val y = 202;
val parts = 4;

def main(args: Array[String]) =
{
  val conf = new SparkConf()
    .setAppName("heat")
    .setMaster("local[%d]".format(parts))
  conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
  conf.set("spark.kryo.registrationRequired", "true")
  conf.registerKryoClasses(
    Array(
      classOf[scala.collection.immutable.Range],
      classOf[scala.collection.mutable.WrappedArray.ofRef[_]],
      classOf[Array[String]]
    )
  )
  val sc = new SparkContext(conf)

  val rootLogger = Logger.getRootLogger()
  rootLogger.setLevel(Level.WARN)
```

`p` is a Partitioner that maps i,j to a particular partition, similar
to Fortran. In Fortran, typically partitions are explicitly fixed to a 
processor, because more-or-less, the number of processors do not change over 
the course of a computation in MPI. This is unlike Spark where there any
be any number of partitions mapped to any number of processes.

That is Fortran `processors = # of partitions`, and Spark `processors <= # of
partitions`.

Here, we map the data to 4 partitions for 4 processes, which are spatially
co-located by i, j. In my experience, this does speed up the computation
(I'm not an expert on the Spark internals). Instead if I used the random
partitioner, it doesn't even complete the first "action" (the job crashes).

```scala
  val p = new DomainPartitioner(x, y)
```

### grid ###

This is the first of three that are replacing the `initialize_grid` function
in the other codes. This is where we initialize our unique keys and their
relation to the i, j points. This is the relationship "position table" that 
we listed above.

The computation is, in a forward-pipe combination:

`cartesian product of i, j | map key_function & create tuples of (key, (i, j))`

The key function that we use is the same sort of one that we used in Thrust.
`RDD` (Resilient Distributed Data) is the base computational unit of Spark, 
where it is a multi-set of tuples, distributed in parallel across the processes.

RDDs can be thought of as a distributed relational table: though, it has
the restriction that you can only join and group on the first column. But, in
practice that's a minor restriction, because you can use map functions to
move your keys to the first column. Actually, this is one of the reasons
that I prefer Flink, because you can express the relationships without
having to do, what I call, "tuple shuffling". (You can use Spark SQL to
express the relationships in SQL instead, which removes this restriction,
but then you aren't programming in Scala any longer, and can have run-time
interpreter errors instead... cie la vie).

```scala
  val is = sc.range(0, x)
  val js = sc.range(0, y)

  val grid : RDD[(Long, (Long, Long))] = is
    .cartesian(js)                             // cartesian product
    .map{ case (i, j) => (i + j * x, (i, j)) } // key function to i, j
    .partitionBy(p)
    .cache()
```

### topo ###

This is the second of three that are replacing the `initialize_grid` function
in the other codes. This is where we initialize the relationship from
unique keys to neighbors, i.e., the stencil relationships. This is the
relationship "topology table" that we listed above.

Notice that we do not use a table of (key, [neighbors]) or 
(key, set(neighbors)]-- this has consequences of how we compute the 
`update_step` later. In Spark, it is possible to store the relationship
this way, as keys to collections, but we choose not to.

There are two reasons for storing the topology this way: One reason 
for doing this is that it is common to represent sets and lists this 
way in a relational data model, and it is related to table 
[normalization](https://en.wikipedia.org/wiki/Database_normalization),
where the identifier for the list or set is a relationship on its elements.
Secondly, this is also how a list would be represented in a flattened 
data-parallel model, like Thrust, where they have to be stored as "segments"
and there are segment identifiers.

The computation is:

`grid | flatmap neighbors | join grid | map create tuples of (neighbor, center)`

We will talk about join below, as this is our first instance of it 
in a computation.

```scala
  val topo : RDD[(Long, Long)] = grid
    .flatMap(adjacent)                                   // determine neighbors
```

#### The almighty JOIN ####

We talked briefly about joins before when we were looking at the analysis
code, and only talked about "equijoins".

A join is a relationship between two tables, based on the values of
one or more columns, creating a new table. There are multiple flavors
of the join: left, right, inner, outer, cross, etc., which I won't go
into because they are outside the scope of this talk, but you can find
more [information](https://en.wikipedia.org/wiki/Relational_algebra)
on them.

The main use for them that we will use them for is "doubly-nested for loops"
or "nested parallelism". What do I mean by that?

It is a common pattern where in many parallel codes that you want to iterate
over one array, list, set in conjunction with another array, list, or set
based on a condition. For example, think of our stencil operation -- **for
all points, we want to map temperature values from all neighbors only if 
they are neighbors.** This is where the join comes in, as it captures this 
conditional pattern.

In a table join, we have two tables that are related on one or more 
attributes. This requires iterating over both tables and doing one
or more filter operations per row (tuple) per table. The type of join 
describes the type of iteration pattern, while the condition is the
functor to the filter. In Haskell, a join is more-or-less (handwaving a 
bit here) `join cond as bs = filter cond [ (a, b) | a <- as, b <- bs ]`

Though, in practice, join on databases are accelerated through indices
and key lookups based on the actual type of join and conditions.

#### Using equijoins for topo ####

So, how does the join help us below? In Spark, `.join` is the equijoin,
that creates a new table that is `[k, (A, B)]` where the input types
are `[k, A]` and `[k, B]`, and returns all rows where `k` matches in the
two tables.

Here, we join `grid after flatmap adjacency` with `grid after swapping tuples`.
If we look at their types, they are basically `((i, j), uid)` and what
we are doing is creating the map of center id => neighbor id by joining them
on the positions i, j.

Whew! That's probably a lot to take in, and I don't expect people to get
it on the first try... but all we are doing is that we have a list that
contains position i,j with it's unique id, and adjacency i,j with the center
id, and creating a table that is just (adjacency id, center id), by matching
adjacency i,j with position i,j from the two lists.

We will see uses of join later (and reduce by key) to do other computations.

```scala
    .join(grid.map { case (id, coord) => (coord, id) }) // join on i, j
    .map { case (coord, (id, adj)) => (adj, id) }       // tuple swizzle
    .partitionBy(p)
    .cache()
```

### temp ###

This is the last of three that replace `initialize_grid` in the other codes.
It is a very simple map of id => temperature, by computing:

`grid | map create tuples of (id, 0.0)`

```scala
  var temp : RDD[(Long, Double)] = grid
    .map { case (id, coord) => (id, 0.0) }
```

### add_random_heat ###

This time around to create the initial conditions, we are able to use
the `takeSample` function from our topology after `filter`ing by the
boundary condition. We are then left with 4 initial points of heat
across our domain, and use a `map` function to set their initial temperature
to 1.0.

To add this back into the list of temperatures for all of the grid points,
we use `union` with the existing temperatures, which are all initialized to 0.
Then using `reduceByKey` with sum, we add the random points back into the
initial condition. 

```scala
  val add_random_heat = grid
    .filter { case (id, (i, j)) => not_boundary(i, j) }
    .takeSample(false, parts)
    .map { case (id, (_, _)) => (id, 1.0) }

  temp = temp
    .union(sc.parallelize(add_random_heat))
    .reduceByKey(p, (a: Double, b: Double) => a + b)
```

`write_output` is found below, but does the same as the other codes, and
writes out the format of the data set. In this version, we do a collect
to the controller (main process) because I didn't want to write a SQLite
virtual table for the `saveTextFile` format that Spark does.

In practical terms, it means my output isn't "scalable" because I am 
bottlenecking on one process, but we have such a small problem, it's not
that big of a deal.

```scala
  write_output(temp.join(grid), 0)
```

## heat program ##

Here is the magic of the main loop that applies the stencil function
via `join`, `map`, and `reduce`.

1. `join` the topology with the temperature -- This results in having a
   table of all the adjacent values temperatures keyed by the center
   point id
2. `map` over (1), multiplying each of the adjacent temperatures by .125  
3. `map` over the original temperatures, multiplying each of them by .5
4. `union` (2) and (3), which results in a table of temperatures keyed
   by the center point id -- We now have all of the necessary new temperature
   values per point to update them
5. complete (4) by `reduceByKey` with sum, which results in a new temperature
   which is assigned back to `temp`

Since Spark is distributed, this will automatically scale out to the
number of workers (processors).

### join vs. ghost cells ###

The one big difference between Fortran and Spark is that in Spark we don't
have to worry about spatial domain decomposition and ghost cells, as
Spark will correctly index all data needed during a join. But, this is
the (likely) biggest performance gain in communication in pre-distribute
of data to the processors that need it and only do a "local" join.

This is where I would like to integrate lessons learned in MPI vs. joins
is providing join operations that allow for particular execution patterns
based on data partitioning and indexing. By relying on an abstract operation
(the shuffle), it becomes really hard to optimize and I personally do
not trust "magic" compilers for parallel code. Possibly someday we will
get there in terms of compiler technology to automatically optimize
distributed computation, but that does not exist as of now.

#### partitioning and sinking ####

In this particular case, I do repartition every so often with our
partitioning function `p`, in both `join` and `reduceByKey`. This
minimizes how many partitions are created after each shuffle. 
This partitioning function tries to replicate how the partitioning
occurs in Fortran. I have noticed a speed difference between partitioning
and not-partitioning (using the default random partitioner).

Also, Spark needs to "sink" every so often, or it may be the case that
you will run out of memory. The DAG, in my experience, will too grow big for it 
to figure out how to evaluate it, since we have such a large iterative loop
around the lazy computation. 

```scala
  var t = 1
  while (t <= 10000)
  {
    temp = topo
      .join(temp, p)
      .map { case (adj, (id, temp)) => (id, .125 * temp) }
      .union(temp.mapValues { case (temp) => .5 * temp })
      .reduceByKey(p, (a:Double, b:Double) => a + b)

    if (t % 100 == 0) write_output(temp.join(grid), t)

    t = t + 1
  }

  sc.stop()
}
```

## miscellany ##

### write_output ###

Instead of using Spark's default write text file functionality, we do
a collect to the master process to transform and write the data to a
single text file.

This is primarily to replicate the existing Fortran file format because
I didn't want to write a second SQLite virtual table for the Spark 
text file format (a number of files per process in separate directories).

```scala
def write_output(grid: RDD[(Long, (Double, (Long, Long)))], t: Integer)
{
  val output = grid
    .filter { case (id, (temp, (i, j))) => not_boundary (i, j) }
    .map { case (id, (temp, (i, j))) => "%d %d %.17e".format(i, j, temp) }
    .collect()

  val p = new java.io.PrintWriter(
    new java.io.File("data/output.0.%d".format(t)))
  try { output.foreach(p.println) } finally { p.close() }
}
```

### not_boundary ###

This is our boundary checking condition to filter out points that are on
the boundary condition.

```scala
def not_boundary(i:Long, j:Long): Boolean = 
  i > 0 && i < x - 1 & j > 0 && j < y - 1 
```

### adjacent ###

`adjacent` is used during the topology construction to construct the
list of points adjacent to each point, as long as it is not a boundary point.

```scala
def adjacent(el: (Long, (Long, Long))): Array[((Long, Long), Long)] =
{
  el match {
    case (id, (i, j)) => {
      if (not_boundary(i, j))
        Array(
          ((i+1L, j), id),
          ((i, j+1L), id),
          ((i-1L, j), id),
          ((i, j-1L), id))
      else Array()
    }
  }
}
```

### DomainPartitioner ###

The domain partitioner replicates the partitioning found in the Fortran
version by building the partition number given the position in the grid
and the size of the grid. As mentioned previously, we don't have ghost
cells and Spark doesn't have the concept of ghost cells.

The closest thing that we could have used for ghost cells is shared data
between all processes/partitions, but this would be incredibly inefficient
for large grids with many processes. Secondly, in the Fortran + MPI code
data is only replicated to processors that it need it, rather than replicating
the data everywhere. Thus, a "join" in Fortran is more like a "localized join",
and does not do an all-to-all communication like a Spark join might do.

```scala
class DomainPartitioner (x: Long, y: Long)
  extends Partitioner 
{
  def numPartitions(): Int = parts

  def getPartition(key: Any): Int = 
  {
    val k = key.asInstanceOf[Long]
    val i = k % x
    val j = k / x
    if (i < x / 2)
    {
      if (j < y / 2) 0
      else 3
    }
    else
    {
      if (j < y / 2) 1
      else 2
    }
  }
}
}
```
