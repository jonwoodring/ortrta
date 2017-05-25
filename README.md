## A Scientist's Guide to the Relational Data Model ##
### or (One Reader to Rule Them All) ###

Jon Woodring, LambdaConf 2017 talk

[Slides](https://cdn.rawgit.com/jonwoodring/ortrta/slides/slides/slides.html)

[Virtual Machine](https://goo.gl/GvU7o0) -- The virtual machine may be out
of date with respect to the git repository, so the first thing you ought
to do is `git pull origin master` after logging in. 

### Requirements ###

All code examples are written in a 
[literate style](https://en.wikipedia.org/wiki/Literate_programming) 
using Markdown, or a notebook format in the case of Python and R.
They all should be directly viewable in GitHub, using the built-in GFM
viewer or notebook viewers.

To run the examples, at a minimum you will need for each: 

- GNU Make
- node.js
- [codedown](https://www.npmjs.com/package/codedown)

The [virtual machine image](https://goo.gl/GvU7o0) already has the 
requirements pre-installed running Arch Linux. As noted before, the
virtual machine may be out of data with respect to the git repository,
so the first thing you ought to do is `git pull origin master` after
logging in.

#### Per Language Requirements ####

- `r` and `dplyr`
  - R
    - dplyr
    - magrittr
    - ggplot2
    - RSQLite compiled with devtools (needs latest SQLite)
  - R-Studio Desktop
- `jupyter`
  - Python 3
    - [apsw](https://github.com/rogerbinns/apsw)
    - numpy
    - Bokeh
  - Jupyter notebook
- `fortran`
  - gfortran
  - MPI (such as OpenMPI or mvapich)
- `haskell`
  - [stack](https://docs.haskellstack.org/en/stable/README/)
- `scala`
  - sbt
- `sqlite`
  - gcc or clang
- `thrust`
  - g++ or clang++
  - Cuda
  - OpenMP
