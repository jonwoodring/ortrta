name := "heat"

version := "0.1.0"

scalaVersion := "2.10.6"

resolvers += Resolver.sonatypeRepo("release")

libraryDependencies := Seq(
  "org.apache.spark" %% "spark-core" % "2.1.0"
  )

fork := true
