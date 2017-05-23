all: heat
	mkdir -p data
	stack exec heat

heat.hs: heat.md
	codedown haskell < heat.md > heat.hs

heat: heat.hs
	stack setup
	stack build

clean:
	rm -rf heat.hs heat data
