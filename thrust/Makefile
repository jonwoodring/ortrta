all: heat
	mkdir -p data
	./heat

heat.cpp: heat.md
	codedown cpp < heat.md > heat.cpp

heat: heat.cpp
	g++ -O3 -DTHRUST_DEVICE_SYSTEM=THRUST_DEVICE_SYSTEM_OMP -lgomp -fopenmp -I/opt/cuda/include/ heat.cpp -o heat

clean:
	rm -rf heat.cpp heat data
