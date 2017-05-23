all: heat.so

heat.c: heat.md
	codedown c < heat.md > heat.c

heat.so: heat.c 
	gcc -Wall -fPIC -shared -O3 heat.c -o heat.so

clean:
	rm -rf heat.c heat.so
