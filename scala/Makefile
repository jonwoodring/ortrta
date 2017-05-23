all: heat
	mkdir -p data
	./sbt run

src/main/scala/heat.scala: heat.md
	mkdir -p src/main/scala
	codedown scala < heat.md > src/main/scala/heat.scala

heat: src/main/scala/heat.scala
	./sbt compile

clean:
	rm -rf src/main/scala/heat.scala data target project/target project/project
