name := "trans-server-akka"

version := "1.4.0"

scalaVersion := "2.11.8"

// Skip test when sbt assembly
// (from: http://www.shigemk2.com/entry/scala_aseembly_part2)
test in assembly := {}
// Change jar name by "sbt assembly"
assemblyJarName in assembly := { s"${name.value}.jar" }

val akkaVersion = "10.0.5"

libraryDependencies ++= Seq(
  // sbt from http://akka.io/docs/
  "com.typesafe.akka" %% "akka-http-core" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaVersion,
  "com.typesafe.akka" %% "akka-http-testkit" % akkaVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaVersion,
  "com.typesafe.akka" %% "akka-http-jackson" % akkaVersion,
  "com.typesafe.akka" %% "akka-http-xml" % akkaVersion,

  // Slick
  "com.typesafe.slick" %% "slick" % "3.1.1",
  "org.slf4j" % "slf4j-nop" % "1.7.21",
  "com.h2database" % "h2" % "1.4.191",

  // ScalaTest
  "org.scalatest" %% "scalatest" % "2.2.6" % "test"
)

enablePlugins(JavaAppPackaging)