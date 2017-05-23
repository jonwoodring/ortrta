# SQLite Virtual Table #

Here are some [advantages](https://sqlite.org/different.html) and disadvantages 
of SQLite, along with appropriate [uses](https://sqlite.org/whentouse.html), 
which is used by many [companies](https://sqlite.org/famous.html). Fourth on 
that list of uses of SQLite is data analysis, and is one of the reasons that I 
am giving this talk, in addition to drawing connections between parallel and
functional computing.

Here, we create our SQLite virtual table over our data set, this will be a
C shared library, i.e., `.so`, `.dylib`, or `.dll`. When we 
`select load_extension` in SQLite, we are loading this implementation here.
SQLite extensions allow you to both write "virtual tables" and custom functions,
such as mapping functions (per row functions) and reduce functions. 

Also, it is possible to compile in your virtual table into SQLite itself,
if load extension is not enabled or you do not want to enable it. Though,
compiling SQLite is relatively easy since it is a single header file and C 
file.

SQLite itself does have a few limitations in how it executes its dialect
of SQL, but they are relatively [minor](https://www.sqlite.org/omitted.html).
One particularly annoying limitation, though, is that it does not support
"windowed functions" over partitions (group by), only reductions (folds).
It is possible to program around this limitation (use "order by"), but it 
would be nice if SQLite directly supported windowing.

### note ###

This entire program is written in a literate programming style. In my personal
work, I use a combination of markdown highlighting in `vi` with `grip` to see 
live updates. Then, I use `codedown` to extract the code for compilation. 
See the `Makefile` for how I achieve this style of programming. 

I've used this in the other example programs, too, though in R and Python I've 
used their notebook formats and editors, R Studio and Jupyter, respectively.

For more notes on literate programming, see Wikipedia -- it was pioneered
by Knuth when he wrote `tex` and I find it really useful. In particular,
`haskell` already has two literate programming modes, both in `latex` and 
`markdown`.

## C implementation ##

SQLite itself is written in C, and its API is C, thus it is very portable.
In particular, I will be showing a native C API implementation of our virtual
table for our scientific data set.

It is possible to write the implementation in any language, as long as
it can do C foreign function interface and export the implementations
of the virtual table API as C exports. For instance, `apsw` for Python
allows you to create virtual tables with Python code.

I will show the core things that need to be implemented, which can be
implemented in any language.

### Implementing the virtual table ###

What we need to implement:

- opening and closing a table
- creating and closing a cursor
- telling SQLite our indices
- starting a query
- going to the next row in a query
- returning row data
- indicating the end of a query

Though, this may sound complex... it's really not if we describe it this way:

- creating a virtual list of named tuples
- creating an iterator into the list of tuples
- describing maps (associative arrays) for tuples values in the list to list 
  positions
- creating a iterator for the list given a filter function
- advancing the iterator, filtering out rows
- returning tuple values at the iterator position
- indicating the end of the iterator

That is:

- tables are lists of named tuples
- cursors are iterators into lists
- an index is a map of values to tuples (positions in the list)

A SQLite virtual table is an API for implementing a traversable (iterator) 
over tuples, which then, the SQLite interpreter can execute SQL queries on it.
That is, a Virtual Table is an iterator implementation over any arbitrary
data set so that SQL queries are able to traverse it.

## C Includes ##

To create an extension we load the `sqlite3ext.h` header that comes with
the SQLite distribution. The initialization requires executing two
C macros, `SQLITE_EXTENSION_INIT1` and `SQLITE_EXTENSION_INIT2`. The
`SQLITE_EXTENSION_INIT2` is called in the code that registers our virtual
table with SQLite.

```c
#include "sqlite3ext.h"
SQLITE_EXTENSION_INIT1

#include <string.h>
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
```

## C Prototypes ##

In the following are our prototypes for the code that we need to implement.
I won't describe them here, but in the following structure that is needed
to tell SQLite about our implementation.

```c
static
int connect(sqlite3 *, void *, int , const char *const *,
            sqlite3_vtab **, char **);
static            
int disconnect(sqlite3_vtab *);
static
int open(sqlite3_vtab *, sqlite3_vtab_cursor **);
static
int close(sqlite3_vtab_cursor *);
static
int best_index(sqlite3_vtab *, sqlite3_index_info*);
static
int filter(sqlite3_vtab_cursor *, int, const char *, int, sqlite3_value **);
static
int next(sqlite3_vtab_cursor *);
static
int column(sqlite3_vtab_cursor *, sqlite3_context *, int);
static
int eof(sqlite3_vtab_cursor *);
static
int rename_t(sqlite3_vtab *, const char *);
```

This is the structure that we need to pass to the SQLite API, and it
describes how to traverse our data set. We are only implementing the
read-only version of the API, which allows us to traverse the files
that we have created from our heat transfer simulation data sets.
The read-only API needs a way to create a table, iterate over that
table given constraints, and return values at each iteration position.

This is similar to implementing a low-level map of identity with a filter
function, such that given the filter functor, it will skip to the next
tuple and return the value at each tuple position.

"We are creating an immutable list of tuples over our data set that
 has built in filtering such that we can start the list at a particular
 position based on a filter arguments."

- Create and Connect : connect to our table implementation, this
  initializes the table state for SQLite
  - In our version, we only have one function for both, because the
    primary difference between the two is that Create creates any
    resources that are needed for the table, such as permanent storage,
    while Connect assumes these have already been created
  - "Create a list of tuples"
- Disconnect and Destroy : disconnect from our table implementation,
  companions to Create and Connect
  - Likewise, Destroy removes any resources that were created by Create,
    while Disconnect just terminates an existing connection
  - Therefore the protocol is, Create Connect+ Disconnect+ Destroy,
    where there are an equal number of Connects and Disconnects called.
    Create and Destroy are only every called once per virtual table invocation.
  - "Destroy a list of tuples"
- Open and Close : create a cursor, i.e, an iterator for our table
  - Here, you initialize any resources that you need to provide an
    iterator over your table implementation. This includes any bookkeeping
    required to know where in the iteration that you might be or values
    of the filter function provided by SQLite.
  - "Create an iterator"
  - "Bind the table to a name"
- Best Index - informs SQLite of any indices (maps of tuple values to
  list positions) on your table, which helps SQLite optimize queries -- 
  as it will use these to skip immediately to the list positions based on
  values.  We will go into more detail on Best Index as it is the most 
  important function to implement for query performance on a virtual table
  - This is the first thing that is called (several times) when a query is
    executed, because SQLite tries to determine the best way to iterate
    over your data, given indices. The next step is to call Filter, which
    starts a query (iterator) at a particular position
  - "Provide any maps from tuple values to list positions"
- Filter - start a query, i.e., a cursor or an iterator, on your data set.
  - This is called after Best Index after SQLite has optimized the query.
    The arguments provided are the indices that you have indicated that 
    exist on your data set, and where to start the iteration.
  - "Filter out tuples and start the list where the filter is true"
- Next - return the next row in a query based on the given filter constraints.
  - SQLite will call this every time it wants to advance to the next row, aka
    tuple, after Filter has been called and until Eof (the end of iteration)
    has been reached. The next tuple should be constrained by the filter 
    information provided by SQLite, such that it skips over values that are 
    not in the filter.
  - "Filter out tuples until the next tuple that meets the filter is true"
- Column - return the value of a column or the value of the tuple position
  - SQLite calls this multiple times per row (tuple) to retrieve the values
    of the current position of the iterator
  - "Get the value of a tuple index"
- Eof - indicate to SQLite that you have reached the end of iteration, either
  through iterating through all data in your data set and there is no more
  data such that the filter is true.
  - "End of list"

### protocol ###

The general protocol for a virtual table API is:

1. `xCreate`
2. `xConnect` - zero or more times
3. `xOpen` - one or more times
4. `xBestIndex` - one or more times before `xFilter` (start of a query)
5. `xFilter` - once after `xBestIndex`
6. `xEof` - called once per row, before `xNext`, `xColumn` or `xRowId`
7. `xColumn` - zero or more times per row if `xEof` is not true
8. `xRowId` - zero or more times per row if `xEof` is not true (only if
              `WITHOUT ROWID` is not set in the schema)
9. `xNext` - called once per row if `xEof` is not true
10. `xClose` - as many times as `xOpen` was called
11. `xDisconnect` - as many times as `xConnect` was called
12. `xDestroy`

1, 2, 11, and 12 are for table creation. 3 and 10 are for cursor creation.
4-9 are for query execution.

### API ###

The static structure `heat_reader` is passed to SQLite API such that it
registers our implementations of the functions required for SQLite to
iterate over our table, aka list of tuples.

The write functions are not implemented, but they are not necessary to
give SQLite read-only access to your data set.

```c
static sqlite3_module heat_reader = 
{
    0,          // version 
    connect,    // xCreate : create table and create any resources
    connect,    // xConnect : connect to table  
    best_index, // xBestIndex : tell the optimizer your indices
    disconnect, // xDisconnect : disconnect from table
    disconnect, // xDestroy : close table and free any resources
    open,       // xOpen : create cursor
    close,      // xClose : close cursor 
    filter,     // xFilter : start a query based on indices (xBestIndex)
    next,       // xNext : advance query
    eof,        // xEof : signal end of query
    column,     // xColumn : return column data at current position
    NULL,       // xRowid : return row unique id at current position
                // ACTUALLY I'M UNCERTAIN IF I HAVE TO DEFINE THIS...
                // I CREATED THE TABLE WITH WITHOUT ROWID, SO I WOULD
                // EXPECT IT IS NOT NEEDED, BUT THE VIRTUAL TABLE
                // WEBPAGE SAYS IT IS -- I SUSPECT IT IS NEEDED ONLY IF
                // WITHOUT ROWID IS NOT PRESENT
    NULL,       // xUpdate : (write) write data 
    NULL,       // xBegin : (write) begin transaction 
    NULL,       // xSync : (write) sync transaction 
    NULL,       // xCommit : (write) commit transaction 
    NULL,       // xRollback : (write) rollback transaction 
    NULL,       // xFindFunction : (optional) custom functions
    rename_t,   // Rename : rename the table
    // stuff after this are SQLite v2 additions
    NULL,       // xSavepoint : (write nested) checkpoint the table
    NULL,       // xRelease : (write nested) remove checkpoint
    NULL,       // xRollbackTo : (write nested) rollback to checkpoint
};
```

### Register the functions with SQLite ###

SQLite will initialize your virtual table by calling `sqlite3_extension_init`
after loading your extension with `select load_extension` as a SQL command.
Here is the second instance of the `SQLITE_EXTENSION_INIT` macro, and
we register all of the functions to implement our virtual table.

```c
int sqlite3_extension_init(sqlite3 *db, char **error, 
                           const sqlite3_api_routines *api)
{
  SQLITE_EXTENSION_INIT2(api);

  return sqlite3_create_module(db, "heat", &heat_reader, 0);
}
```

## State ##

In our implementation, we will keep track of two bits of state, the table
and the cursor (the list and the position in the list). These are the
only bits of state we will need to keep track of.

SQLite will pass a reference to the "base" table and cursor that SQLite 
uses itself to keep track of its internal state to each of our functions.
We wrap that base implementation with our additional data that we need
to keep track of our own state around the base data that SQLite uses.
Thus, when SQLite passes a reference to the table or cursor, we are able to
keep track of our own state, because it is contained within the reference
pointer.

Therefore, it isn't necessary to have any global state that keeps track of
our table, we can add our particular state pieces to SQLite tables and cursors.

The important thing is that the base SQLite table and cursor comes first
in the C structure, because SQLite expects the reference (pointer) to be
its table and cursor type, and we are appending our state to the end of it.

### heat_table ###

An extension of the base `sqlite3_vtab` table, such that we keep track of
the `path`, or directory, to the heat simulation files.  We will create this 
state during a `connect` function, and clean it up during a `disconnect` 
function.

When our virtual table is initialized, `connect` is called during
`CREATE VIRTUAL TABLE <name> USING heat(<path>)` in SQL. `<path>` is an
argument string to the directory where the files are stored. The path to these
files will be stored using `*path` inside our table state, below.

```c
struct heat_table 
{
  sqlite3_vtab table;

  char *path;
};
typedef struct heat_table heat_table;
```

### heat_cursor ###

An extension of the base `sqlite3_vtab_cursor`, such that we keep track
of the iteration state, or where the current row position is in our virtual
table. We will create this during an `open` function, and clean it up
during a `close` function. Additionally, when `filter` is called, i.e.,
the start of a query, we will store the state of the filter arguments in
the cursor. `next` will update the state of the cursor, i.e., move the
position of the list to the next row that satisfies the filter arguments.

Whenever SQLite starts a query, it will create a cursor object (or reuse
an existing one) to keep track of where it is currently, row-wise, in your
table. This gets passed into `filter`, `next`, `column` and `eof` to be
able to provide SQLite the data for the current position of iteration or
move the iteration to the next position.

```c
struct heat_cursor
{
  sqlite3_vtab_cursor cursor;
```

A reference to the table, to be able to get `*path`.

```c
  heat_table* table;
```

These are the arguments to the `filter` function to be able to keep track of
it while iterating.

```c
  int operator;
  int value;
```

Current position of the iterator that are updated during `next`. We keep
track of iteration by tracking the current file and the current position 
in the file.

```c
  DIR* directory;
  FILE* file;
```

Row values that are parsed from the current file, such that when `column`
is called, we return the values for the current row.

```c
  int row_i;
  int row_j;
  double row_heat;
  int row_processor;
  int row_time;
};
typedef struct heat_cursor heat_cursor;
```

## Static Data ##

### CREATE TABLE STRING ###

`CREATE_VTAB_STRING` is an important bit, because it tells SQLite the
structure, aka schema, of the virtual table that we are trying to create.
We use a `CREATE TABLE` SQL syntax, which informs SQLite the structure
of our virtual table -- this is basically informing SQLite the structure
of our "list of named tuples".

This does not have to be static, as the schema is created when `connect`
is called. Thus, if you have a data set that has a more dynamic structure,
you can create the structure on the fly, by dynamically creating a 
`CREATE TABLE` statement on the fly from the structure of the data set
as it is on storage.

Here since our data does have a static structure, it's just a list of
files with a well defined structure in the files, we predefine it in this
string.

Secondly, I'm using a newer feature of SQLite (v3.14), the `WITHOUT ROWID`
in the `CREATE TABLE` statement. `WITHOUT ROWID` is used in the cases
where it is hard to define a unique integer (UID) for `rowid`, which SQLite
requires for every row, to uniquely define rows, i.e., `rowid` is a hashing
function with uniqueness guarantee. While it isn't entirely hard to create
a UID for our row values (could use i, j, processor, time), with this new
feature, it's just easier to use those columns and tell SQLite to
create a `PRIMARY KEY` using them.

This does require checking our SQLite version is >= 3.14, which can cause
problems if you are trying to use a virtual table in older versions of
SQLite. In the Python and R that is on the VM, I have recent enough versions,
though that did require building RSQLite using `devtools` extension in R,
because the default SQLite in RSQLite from CRAN is old.

#### the structure ####

The structure we will present to SQLite is a table of:

- `i` *integer* - the i position of a point
- `j` *integer* - the j position of a point
- `heat` *real* - the temperature at i, j
- `processor` *integer* - the processor that generated this i, j
- `time` *integer* - the time step for this i, j, heat

We can also think that we "typing" a list of 5-tuples, where i is the first
value in the tuple, and time is the last value in a tuple.

```c
#define CREATE_VTAB_STRING "CREATE TABLE heat (i INTEGER, j INTEGER, heat REAL, processor INTEGER, time INTEGER, PRIMARY KEY(i, j, processor, time)) WITHOUT ROWID"
```

Just the filename pattern for our files.

```c
#define FILENAME_PATTERN "output.%d.%d"
```

Define the maximum line read from our files.

```c
#define MAX_ROW_READ 256
```

The column (tuple) index for the time column.

```c
#define TIME_COLUMN_INDEX 4
```

Some error messages for error reporting.

```c
#define NO_PATH_ERROR "No path was given for the database."
#define UNABLE_TO_OPEN_ERROR "Unable to open path for the database: "
#define SQLITE_VERSION_ERROR "Virtual table requires SQLite 3.14 or greater."
#define DECLARE_VTAB_ERROR "Unable to declare virtual table: "
```

## C Implementations ##

In the following, we have our implementations for the different necessary
bits. As I mentioned previously, it is quite possible to implement the
API in another language if it is able to do C exports of the functions
and has a foreign function interface. For example, python `apsw` allows you
to write virtual tables directly in Python, and I have rapid prototyped
several in that manner.

### connect ###

`connect` establishes a connection to our virtual table. SQLite will
call this first when `CREATE VIRTUAL TABLE` is executed using your
module, which in this case is our `heat` module. Thus the string
in SQLite looks like:

`CREATE VIRTUAL TABLE <table name> USING heat(<path to files>);`

We use this function in both the `xCreate` and `xConnect` API, and
the difference is that `xCreate` is called the first time a connection
is opened to a table, allowing you to create any resources, and `xConnect`
assumes that `xCreate` has been called once already -- i.e., establishing
a new connection to an existing table.

Since we are not distinguishing between the two, we just have one
function `connect` that is used for both `xCreate` and `xConnect`.

`connect` is passed

- `db` *input* - the reference to the SQLite database itself
- `aux` *input* - auxiliary data that is not used in this case
- `argc` *input* - the number of arguments passed to the module
- `argv` *input* - the list of arguments passed to the module
- `vtab` *output* - the created table memory that is passed back to SQLite
- `error` *output* - any error message to pass back to SQLite 

The main things that we will do:

1. parse the directory argument passed to us via `USING` clause
2. check to see if we can open the directory that the files are in
3. declare our schema
4. allocate memory for the table and send it back to SQLite

```c
static int connect(sqlite3 *db, void *aux,
                   int argc, const char *const *argv,
                   sqlite3_vtab **vtab, char **error)
{
```

Initialize the pointers to 0, i.e., `NULL`. I prefer using 0.

```c
  *error = 0;
  *vtab = 0;
```

Check to see if we are version 3.14 or greater. We need this to be able
to use `WITHOUT ROWID`. It is quite possible to create this virtual
table without using `WITHOUT ROWID`, and it would require implementing
the `rowid` function. In that case, you need to have a function that
maps rows to unique id values.

When using `WITHOUT ROWID`, instead you have to specify rows that create
a `PRIMARY KEY`, i.e., a unique value. In our case, we use i, j, processor,
time, because they are unique per row.

```c
  if (sqlite3_libversion_number() < 3014000)
  {
    *error = sqlite3_malloc(strlen(SQLITE_VERSION_ERROR) + 1);
    strcpy(*error, SQLITE_VERSION_ERROR);
    return SQLITE_ERROR;
  }
```

When SQLite creates your virtual table, it is called via:
`CREATE VIRTUAL TABLE <name> USING <module>(<args>);` `<args>` are
an arbitrary comma separated list, which are passed to your virtual
table implementation via `argc` and `argv`. Thus, you can parameterize
your virtual table in arbitrary ways, with any sort of argument lists
that you want.

In our case, we just have 1 argument, the path to where our files are stored.
This will be position 3 in argv, i.e., `argv[3]`.

Thus, we check if we have enough arguments and get the path that is
passed to us from SQLite.

```c
  // argv[0] is the module name
  // argv[1] is the database name
  // argv[2] is the table name
  // argv[3+] are arguments passed to CREATE VIRTUAL TABLE in the USING clause
  //
  // thus argv[3] will be the path to our files
  if (argc < 4)
  {
    *error = sqlite3_malloc(strlen(NO_PATH_ERROR) + 1);
    strcpy(*error, NO_PATH_ERROR);
    return SQLITE_ERROR;
  }
```

Below, we do some allocation and parsing of the argument string that is
passed to us, primarily to remove any " or ' at the beginning or end of the
string.

```c
  // malloc some space to copy the string
  char *path = sqlite3_malloc(strlen(argv[3]) + 1);
  if (!path)
  {
    return SQLITE_NOMEM;
  }
  char *path_copy = sqlite3_malloc(strlen(argv[3]) + 1);
  if (!path_copy)
  {
    sqlite3_free(path);
    return SQLITE_NOMEM;
  }

  // create our path string and do some parsing to make sure it is OK
  // i.e., we strip any " and ' from the beginning or end of the string
  // because SQLite will pass us the "USING" argument as-is from SQL
  strcpy(path_copy, argv[3]);
  if (path_copy[strlen(path_copy)-1] == '\'' || 
      path_copy[strlen(path_copy)-1] == '"')
  {
    path_copy[strlen(path_copy)-1] = 0;
  }
  if (path_copy[0] == '\'' || path_copy[0] == '"')
  {
    strcpy(path, path_copy + 1);
  }
  else
  {
    strcpy(path, path_copy);
  }
  sqlite3_free(path_copy);
```

Test that we can actually open the path that was given to us, and if not,
error out.

```c
  DIR* dir = opendir(path);
  if (!dir)
  {
    *error = 
      sqlite3_malloc(strlen(UNABLE_TO_OPEN_ERROR) + 
                     strlen(path) + 1);
    strcpy(*error, UNABLE_TO_OPEN_ERROR);
    strcpy(*error + strlen(UNABLE_TO_OPEN_ERROR), path);

    sqlite3_free(path);
    return SQLITE_ERROR;
  }
  closedir(dir);
```

Up to this point we seem to be OK on the path name for the directory
that the files are in, so now we try to declare our schema to SQLite
through `sqlite3_declare_vtab`. Earlier, we had a discussion on
`CREATE_VTAB_STRING` and the significance of the structure. 

If our `CREATE TABLE` string is malformed, SQLite will error, and we
need to error as well.

```c
  int retval = sqlite3_declare_vtab(db, CREATE_VTAB_STRING);
  if (retval != SQLITE_OK)
  {
    *error = sqlite3_malloc(strlen(DECLARE_VTAB_ERROR) +
                            strlen(sqlite3_errmsg(db)) + 1);
    strcpy(*error, DECLARE_VTAB_ERROR);
    strcpy(*error + strlen(DECLARE_VTAB_ERROR), sqlite3_errmsg(db));
    sqlite3_free(path);
    return SQLITE_ERROR;
  }
```

Everything has been OK, so now we allocate the memory for the virtual
table. Remember earlier we wrapped the base `sqlite_vtab` structure
in our own custom table structure `heat_table`. This allows us to store
our own custom data, in this case, the path to the directory that our
files are stored in.

After creating `heat_table`, we store the directory path and give the
table back to SQLite.

```c
  heat_table *table = (heat_table *)sqlite3_malloc(sizeof(heat_table));
  if (!table)
  {
    sqlite3_free(path);
    return SQLITE_NOMEM;
  }

  table->path = path;
  *vtab = (sqlite3_vtab *)table;

  return SQLITE_OK;
}
```

### disconnect ###

`disconnect` is the equivalent to `connect`, such that any memory or
resources associated with a table ought to be cleaned up. Likewise with
`xCreate` and `xConnect`, SQLite API has `xDestroy` and `xDisconnect`
for cleaning up resources associated with a table, and disconnecting from
a table without cleaning up resources. Since we only have `connect`,
we only have `disconnect` that will clean up our memory for the virtual
table.

```c
static int disconnect(sqlite3_vtab *vtab)
{
  // cast it to "heat_table" because that's what it really is
  heat_table *table = (heat_table *)vtab;

  // free our directory path string stored in it
  sqlite3_free(table->path);
  // free up the rest of the table itself
  sqlite3_free(table);

  return SQLITE_OK;
}
```

### open ###

`open` creates a cursor, or otherwise known as an iterator, to our table.
A cursor allows SQLite to iterate over your data set given a query,
and as such, we need to keep the state of the query in the cursor.
This is because SQLite will continually advance the cursor with `next`,
and we will need to keep track of where we currently are in our data set.

One of the big differences between a "list of tuples" and a "table", is
that tables conceptually have the ability to skip forward through indexing,
like arrays. Thus, when applying filters, iterating over a table is able to 
quickly skip over large chunks of row data by skipping to or skipping over 
rows that do not meet a filter function.

The state that we need to keep track of: where we are in our table (row),
and what are the arguments to the filter that SQLite wants to skip over.
For now, we will just initialize the cursor, and the arguments for the
filter function are provided later by SQLite in `best_index` and `filter`.

Like with `sqlite3_vtab` and how we wrapped it with a `heat_table`,
we will take the base `sqlite3_vtab_cursor` and wrap it with our
`heat_cursor` state.

`cursor` is provided:

- `vtab` *input* - the virtual table that the cursor is created on
- `vcursor` *output* - an allocated cursor that has our cursor state

```c
static int open(sqlite3_vtab *vtab, sqlite3_vtab_cursor **vcursor)
{
  // allocate the memory for the cursor
  heat_cursor *cursor = sqlite3_malloc(sizeof(heat_cursor));
  if (!cursor)
  {
    return SQLITE_NOMEM;
  }
```

Here, we just initialize the `directory` and `file` pointers to 0,
because we will have the list of files open and a particular file. The
other parts of the state, i.e., the row information or position in the
`file`, will be initialized later in the `filter` function. In particular,
we initialize them to 0 so that we know if the `directory` or `file`
is pointing to a currently open directory or file when we clean up the
cursor state.

```c
  cursor->table = (heat_table *)vtab;
  cursor->directory = 0;
  cursor->file = 0;
  *vcursor = (sqlite3_vtab_cursor *)cursor;

  return SQLITE_OK;
}
```

### close ###

`close` is the corresponding function to `open`, it cleans up any
resources associated with a cursor, like `disconnect` is to `connect`
for tables.

In particular, if we have an open `directory` or open `file` we will
need to close them, so that they are not left open for the OS.

```c
static int close(sqlite3_vtab_cursor *vcursor)
{
  // cast the cursor to our cursor
  heat_cursor *cursor = (heat_cursor *)vcursor;

  // if a file is open, close it
  if (cursor->file)
  {
    fclose(cursor->file);
  }
  // if the directory is open, close it
  if (cursor->directory)
  {
    closedir(cursor->directory);
  }

  // clean up the memory
  sqlite3_free(cursor);

  return SQLITE_OK;
}
```

### best_index ###

`best_index` tells SQLite any indices that you might have on your data set.
This is a handshaking protocol that the SQLite query optimizer and
your virtual table implementation. It will call this several times and
repeatedly asks your implementation "do you have an index on column X and if so,
how expensive is it to use it?" It may call `best_index` multiple times
depending on the query.

The implementation of `best_index` can be the simplest or most complex 
function to implement in the API depending on the indexing capabilities that 
you want to provide to SQLite in iterating and searching through your data 
set. For example, this function could have an empty implementation, i.e.,
there are no indices and then SQLite will scan through your data during
each query.  Therefore, the indices that you provide to SQLite allows it
to potentially traverse your data set faster based on the filter.

How to implement "indexing" is totally up to your implementation:
it could use tree structures, bitmap indexing, hash maps, etc. SQLite does 
not dictate how indexing is implemented, just the operator capabilities of 
the filter function. To see the types of operations SQLite will do,
see the `SQLITE_INDEX_CONSTRAINT_`s in the documentation.

#### implementing time indexing ####

Below, we will provide on index on the "time" column, i.e., we will allow
SQLite to skip over rows based on time range queries. Recall that our
files that we wrote out of our simulation are per time step, and such,
we have provided a time query column in our output. Therefore, we can
skip over entire files that do not have the time steps of interest in the
query.

This is done by using the `info` structure passed to us by SQLite, which
has information on the type of query it is trying to perform, and in
return, we fill in information in the `info` structure as well based
on the query. 2.3 in the [virtual table](https://sqlite.org/vtab.html)
information page has more detail on `xBestIndex` and the `info` 
(`sqlite3_index_info`) structure.

`best_index` is passed:

- `vtab` *input* - the virtual table that the query is going to start on
- `info` *input/output* - information on the query, and our response on
                          the indices that we can provide for that query

Our implementation will do:

1. check to see if a query is on the time column
2. if so, see if it's a type of query we can handle
3. tell SQLite we have an index and the "estimated cost" to use it
4. if not, provide a large estimated cost

Actually, it's a little bit of a lie that we are "telling" SQLite our
indices, really we are telling SQLite the cost of using our indices,
which helps the query optimizer plan its execution on our data set.
That is, SQLite does actually care what the indices are, other than
how much it costs and what columns are being indexed. The information
that is stored in `idxNum` and `idxStr` are actually passed back to
us in the `filter` function -- therefore, we are parameterizing the
index that we are using and building an argument list to ourselves in
the `filter` function.

For example, if you have a complex index, you can serialize the arguments
for it in `idxStr` and then deserialize the arguments in the `filter`
function. This allows us to build any arbitrary index data and pass the
parameterizations for the index from `best_index` back to ourselves in i
`filter`.  In our implementation below, we will pass index data in `idxNum`,
with the type of operation that SQLite wants to do on the time column.

```c
static int best_index(sqlite3_vtab *vtab, sqlite3_index_info* info)
{
  // initialize the idxNum to 0 -- idxNum and idxStr are passed
  // back to us in the "filter" function - SQLite doesn't actually
  // care what the values are, they are used to parameterize our 
  // own indexing functions
  info->idxNum = 0;
  // this is just a big num, and anything with an index should be smaller
  // (it's the number of rows in our total data set, 200 * 200 * 10000)
  info->estimatedCost = 400000000;
```

Iterate through all of the constraints that SQLite wants to perform on our
data set. We check to see if any of the columns are the time column
(`TIME_COLUMN_INDEX`) and if it's a filtering operation that we can handle,
i.e., one of the `SQLITE_INDEX_CONSTRAINTS_` for numeric range queries,
like equals, less than, greater than, etc. 

To store this operation, we set a bit mask on `idxNum` with the operation
value SQLite is looking to use on the time column. `idxNum` will then
be passed to `filter` if it is used during the query, and thus we know
the operation to filter on the time column. 

There's other ways to implement passing this indexing data, for example
if we had actual static indexing structures, like a tree or hash map.
Then we could use `idxNum` or `idxStr` to refer to one of these index data
sets.

*The "build an index" algorithm below is:*

1. iterate over all constraints that SQLite is providing
2. see if any of the columns are a time column
3. if so, make sure it is an operator we handle (range queries) and
   set `idxNum` for the operator number
4. set `estimatedCost` to something smaller 

```c
  // we only do it for one time value, if there is more than one,
  // we ignore it
  for (int i = 0; info->idxNum == 0 && i < info->nConstraint; i++)
  {
    if (info->aConstraint[i].usable &&
        info->aConstraint[i].iColumn == TIME_COLUMN_INDEX &&
        // check the operation if it one that we handle
        info->aConstraint[i].op < SQLITE_INDEX_CONSTRAINT_MATCH &&
        info->aConstraint[i].op != SQLITE_INDEX_SCAN_UNIQUE)
    {
      // if it is time, put the operation in a bit mask in "idxNum"
      info->idxNum |= info->aConstraint[i].op;
      // and tell SQLite we are going to use that column
      // argvIndex=1 tells SQLite to give us the time column as argument 0,
      // i.e., the argument filter gets is n - 1
      info->aConstraintUsage[i].argvIndex = 1;
      // we'll estimate that we half the cost for every index or
      // the size of an individual file if it's a equality query
      if (info->aConstraint[i].op == SQLITE_INDEX_CONSTRAINT_EQ)
      {
        info->estimatedCost = 40000;
      }
      else
      {
        info->estimatedCost = 200000000;
      }
    }
  }

  return SQLITE_OK;
}
```

### filter ###

`filter` is the actual start of a query, such that we need to start
the iterator (cursor) at the position asked for by SQLite. 

Previously, `best_index` was called multiple times by SQLite to determine
if there are any indices on our data set, and in response, we return
index configurations based on the constraints that SQLite is looking for.
SQLite will call `filter` with what it determines to be the most
optimal query, which may or may not be one of the indices that was
"constructed" during `best_index` and parameters returned back through
`idxNum` and/or `idxStr`.

If SQLite determines that it wants to use one of your indices, it will
pass `idxNum` and `idxStr` with the configurations that were passed
back after calling `best_index`. But, `idxNum` and `idxStr` have no
intrinsic meaning to SQLite, the values are whatever you provided at
the time from `best_index`, therefore they are configuration values
to determine the type of index that you are building for SQLite, i.e.,
they are user defined.

In our actual implementation, we only have an index on time -- this is
because in `best_index` we set `idxNum` to a non-zero value (the
mask of the constraint operator) if there is a time constraint. Otherwise,
we have no "index" (i.e., no filter configuration).  This means if there is 
no filter parameterization, SQLite will visit every single row in our data set
using `next`.  If there is a constraint, our implementation of `next` will 
skip the rows that SQLite doesn't want, i.e., skip all time values that are 
not in the constraint.

Therefore, `filter` is configuring a filter functor -- SQLite is providing
values that tells your implementation which values it wants to include
(or exclude). The `best_index` process is a way to tell SQLite when
you have an index on a column, which then allows your implementation to
quickly jump to the next element in a data set without having to
scan the entire data during filtering.

It is never necessary to create indices for correctness, because SQLite will
drop rows it doesn't want. What it does do though, between `best_index` and 
`filter`, is that it allows SQLite to optimize iteration by having your
virtual table implementation exclude data that are outside of a `WHERE` clause. Or it can speed up `JOIN` operations, by providing indexing to jump
immediately to rows based on column values, rather than traversing the entire
data set.

`filter` arguments are:

- `vcursor` *input/output* - the cursor (iterator) that keeps our iteration
                             state over calls to `next`, `cursor`, and `eof`
- `idxNum` *input* - index configuration that is user supplied from calling
                     `best_index` - it will have the `idxNum` that SQLite
                     has determined is the best way to traverse your data
- `idxStr` *input* - more index configuration, like `idxNum`, such that you
                     can serialize data in the string that is passed to 
                     `filter` from `best_index`
- `argc` *input* - the number of constraints in `argv`, such that they are the
                   values associated with the operation constraints 
- `argv` *input* - constraint parameters set in `best_index` by configuring
                   `info->aConstraintUsage[i].argvIndex` -- in `best_index`
                   if you set a `aConstraintUseage[i] = 1` it will be
                   `argv[0]` here, i.e., it is -1

The `filter` implementation below:

1. sees if `idxNum` is anything other than 0 -- if so, sets the
   `value` constraint, which is time value constraint from some `WHERE` or 
   `JOIN` operation
2. opens the directory to where are files are
3. advances the cursor to the first valid row by calling `next` -- if
   there are no valid rows, for instance the time query range is outside
   of the file numbers, `next` will eventually reach the end of the
   directory and close it -- in that case, `eof` will be true

```c
static int filter(sqlite3_vtab_cursor *vcursor, int idxNum,
                  const char *idxStr, int argc, sqlite3_value **argv)
{
  // cast the base cursor to our specialized one
  heat_cursor *cursor = (heat_cursor *)vcursor;
```

See if we actually have a time index, i.e., `idxNum` will be non-zero.
If it is non-zero, we set the `operator` on the `cursor`, which is a 
`SQLITE_INDEX_CONSTRAINT_`.  We then get the value of the constraint,
which is in `argv[0]` (by setting that in `best_index`).

```c
  cursor->operator = idxNum;
  if (idxNum > 0)
  {
    cursor->value = sqlite3_value_int(argv[0]); 
  }
```

After that, open the `directory` and advance the cursor with `next`.

```c
  // open the directory to the files
  cursor->directory = opendir(cursor->table->path);
  cursor->file = 0;

  // advance the cursor to the first valid row, and in this case
  // next may end up skipping all of the data if the time query is
  // out of range of the files
  next(vcursor);

  return SQLITE_OK;
}
```

### filter_by_time (not public) ###

`filter_by_time` is how we implement our indexing on the time column.
This is called by `next` to determine whether or not to skip row(s).

In `filter`, we stored the `operator` and `value` of the filtering
constraint that SQLite is looking to do on the time column. At this point
when `filter_by_time` is called, we have the data for the current row
stored in the `cursor` (`row_time`). To filter out data, we just do a 
comparison on `row_time` against `value` (the time value) based on the 
`operator`.

The algorithm is simple, given a `cursor`:

1. if the operator happens to be 0, return true
2. else compare `row_time` against `value` based on `operator`
3. return the result of the comparison

As an exercise for the reader, I have not actually implemented a whole
lot of virtual table side filtering of the data. We could have easily
implemented filtering by i, j, heat, and processor too. It is faster to
do it here, if you are concerned with speed, as SQLite itself is an
interpreted language and interpreter. The interpreter will correctly filter 
out rows by testing the values itself. 

We can accelerate filtering here, by setting the `omit` flag in
`best_index`, which will tell SQLite not to check the values, and doing all
filtering in the `next` function.

```c
static int filter_by_time(heat_cursor *cursor)
{
  // if there's no operator, return true
  if (cursor->operator == 0)
  {
    return 1;
  }

  // otherwise, compare "row_time" against "value" based on "operator"
  int retval = 1;
  if (cursor->operator & SQLITE_INDEX_CONSTRAINT_EQ)
  {
    retval &= cursor->row_time == cursor->value;
  }
  if (cursor->operator & SQLITE_INDEX_CONSTRAINT_GT)
  {
    retval &= cursor->row_time >  cursor->value;
  }
  if (cursor->operator & SQLITE_INDEX_CONSTRAINT_LE)
  {
    retval &= cursor->row_time <= cursor->value;
  }
  if (cursor->operator & SQLITE_INDEX_CONSTRAINT_LT)
  {
    retval &= cursor->row_time <  cursor->value;
  }
  if (cursor->operator & SQLITE_INDEX_CONSTRAINT_GE)
  {
    retval &= cursor->row_time >= cursor->value;
  }

  // return the comparison
  return retval;
}
```

### next ###

`next` advances our cursor (iterator) to the next row of data. This is
where we do the bulk of our work, as we will open files and read the
data from the files to be able to return it to SQLite.

The structure of our data set is that we have files in a directory, and
each of the files has a line of data. Our algorithm to iterate over all
of the data is simple:

- while we haven't reached the last file
  - get next file name
  - parse the file name (time and processor)
  - filter file if its time step is out of range
  - open a file if it isn't filtered out
    - while we haven't reached the end of the file
      - read line 
      - parse line (i, j and heat) and return
    - close file
- close directory

The "filter file if its time step it out of range" is where the magic happens.
We call `filter_by_time` and apply the "indexing" information that was
created during `best_index` and `filter`. This is where we will get
a speed benefit whenever the query is performed using constraints on
the time column. We won't need to read a file, as we can skip entire files
if their time step isn't in the query range, i.e., the time value
is filtered out.

#### structuring unstructured data ####

Think back to the structure of our table, it is

- `i` *integer*
- `j` *integer*
- `heat` *real*
- `time` *integer*
- `processor` *integer*

Here is where something interesting happens is that we are able to
easily create relations, i.e., structure our data, from something that is
initially unstructured: the `time` and `processor` columns. They are not
columns in the files, but rather, they are "meta-data" in the file name.

This is one of the benefits of SQLite is the ability to create a structured
view of any data set that may not initially seem structured, and then 
being able to query over this seemingly "meta-data" that is part of the file
name. Now, it is trivial to query by time and or processor, and do
things like `SELECT time FROM heat WHERE heat > .5` to show all of the time
steps where the heat is greater than .5 or 
`SELECT max(heat) FROM heat WHERE time = 1700`. For example, there are
virtual table implementations for textual data and even JSON data sets.

```c
static int next(sqlite3_vtab_cursor *vcursor)
{
  heat_cursor *cursor = (heat_cursor *)vcursor;
```

We set loop sentinel `row_ok` to break out of the loop. I could have returned
directly after parsing a line, but I prefer having one exit point out of a 
function.

**While we haven't reached the end of the directory (no more files)...**

```c
  int row_ok = 0;
  // iterate until the last file
  while (!row_ok && cursor->directory)
  {
```

**If a file is open and we haven't reached the end of the file, read a line
  of data and parse it. We store the parsed data in `cursor`.**

**If we have reached the end of the file, close it -- the next iteration
  of the loop will open the next file in the directory.**

```c
    // if file is open
    if (cursor->file)
    {
      // close file if we are at the end of the file
      if (feof(cursor->file))
      {
        fclose(cursor->file);
        cursor->file = 0;
      }
      // otherwise, parse a line of data and store it in the cursor
      else
      {
        row_ok = fscanf(cursor->file,
          "%d %d %lf", &cursor->row_i, &cursor->row_j, &cursor->row_heat)
          == 3;
      }
    }
```

**If a file isn't open, read the next file name from the directory. Then,
  parse the file name only if we haven't reached the end of the directory.**

**Parsing the file name sets the processor and time data for the row(s). Also, 
  we filter out files that have time values that are not in the query range
  by calling `filter_by_time`, and skip to the next file.**

As mentioned before, this is where we can get a major processing speed benefit 
by skipping over files that do not meet query (`filter`) criteria. It is
up to your implementation to create as many or as few indices as you want. 
For instance, you could imagine building a hash-map for file content to be
able to filter or index by i, j, or heat. Though, building a hash-map
or any heavy-duty indexing structure is probably over-kill for this data
set. Or just skipping over values of i, j, and heat after reading them,
rather than sending them back to SQLite, thereby speeding up the processing,
by not having to send unnecessary data back to the SQL interpreter.

There's also other things we could do, like caching the directory
structure, and reading in the file names from disk, then storing them in a 
list in the table. Again, it's not clear if this would be faster because
it is likely the OS is caching the directory structure anyways. **Anyways,
the sky is the limit on building indexing structures for your data,
but I would certainly not worry about pre-optimization, because if your
queries are fast enough without indexing, then don't worry about it.**

```c
    // no file is open, so read the next directory entry
    else 
    {
      struct dirent *entry = readdir(cursor->directory);

      // if we've reached the end of the directory, close it
      // and now we've reached the end of the query
      if (!entry) 
      {
        closedir(cursor->directory);
        cursor->directory = 0;
      }
      // otherwise, parse the file name
      else
      {
        int items = sscanf(entry->d_name, FILENAME_PATTERN, 
                           &(cursor->row_processor), &(cursor->row_time))
          == 2;

        // if we've parsed the file name, only open the file
        // if we don't filter it out by time --
        // that is, if we don't open a file, the loop will come back
        // around and try opening the next file
        if(items && filter_by_time(cursor))
        {
          char *fn = sqlite3_malloc(strlen(cursor->table->path) +
                                    strlen(entry->d_name) + 2);

          strcpy(fn, cursor->table->path);
          strcpy(fn + strlen(cursor->table->path), "/");
          strcpy(fn + strlen(cursor->table->path) + 1, entry->d_name);

          cursor->file = fopen(fn, "r");

          sqlite3_free(fn);
        }
      }
    }
  }

  return SQLITE_OK;
}
```

### column ###

`column` is how we return our row (tuple) data, as SQLite will call this
several times after iterating to the next row. Previously in 
`next`, we have already parsed the data from a line per file and filename
and stored it in `cursor`. So, we just return the data from `cursor`.

Arguments to `column` are:

- `vcursor` *input* - the cursor at the current row advanced by `next`
- `context` *input* - SQLite context to return the data to via `sqlite3_result`
- `column` *input* - the column index value to return, as determined by our 
                     schema, which we declared all the way back in `connect`

One thing to note is if you want to return `NULL` values (no value), there
is a special return clause `sqlite3_result_null`. It is also possible
to return "blob" data, i.e., arbitrary binary data via `sqlite3_result_blob`
and string/text data via `sqlite3_result_text`.

```c
static int column(sqlite3_vtab_cursor *vcursor, 
                  sqlite3_context* context, int column)
{
  heat_cursor *cursor = (heat_cursor *)vcursor;
  // i 
  if (column == 0)
  {
    sqlite3_result_int(context, cursor->row_i);
  }
  // j
  else if (column == 1)
  {
    sqlite3_result_int(context, cursor->row_j);
  }
  // heat
  else if (column == 2)
  {
    sqlite3_result_double(context, cursor->row_heat);
  }
  // processor
  else if (column == 3)
  {
    sqlite3_result_int(context, cursor->row_processor);
  }
  // time
  else if (column == 4)
  {
    sqlite3_result_int(context, cursor->row_time);
  }

  return SQLITE_OK;
}
```

### eof ###

`eof` will be called by SQLite before calling `next` and/or `column` and will
be called once after `filter`. It is used to determine if the cursor is 
pointing at a valid row of data, i.e., it hasn't reached the end of a query.

It is important that `filter` starts at the first valid row of
data, because `next` is not called after `filter`. Rather, `eof` is called
first. Thus, `filter` ought to start at the first valid row of data, and
if not, it should be at the end so that `eof` is true.

```c
static int eof(sqlite3_vtab_cursor *vcursor)
{
  heat_cursor *cursor = (heat_cursor *)vcursor;

  return cursor->directory == 0;
}
```

### rename_t ###

`rename_t` is a required API function for renaming a table. In our case,
we do nothing, but in some implementations they may want to update some
internal or external data based on the new name. In particular, if we had 
implemented the table mutation functions to support `INSERT`, `UPDATE`, or 
`DELETE`, then a rename would create a new name binding for our table.
After receiving a new name, SQLite semantics have it such that unique table
names ought to be independent values, i.e., no name to value aliasing. 

`VIEW`s for instance do allow aliasing, but are read-only aliases, while 
`TABLE`s ought to be separate copies based on name.

```c
static int rename_t(sqlite3_vtab *vtab, const char *new_name)
{
  return SQLITE_OK;
}
```
