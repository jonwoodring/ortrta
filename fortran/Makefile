all: heat
	mkdir -p data
	mpirun -np 4 ./heat

heat.f90: heat.md
	codedown fortran < heat.md > heat.f90

heat: heat.f90
	mpifort -O3 heat.f90 -o heat

clean:
	rm -rf heat.f90 heat heat_transfer.mod data
