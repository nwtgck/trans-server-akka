name := "trans-server-akka"

version := "1.0.0"

scalaVersion := "2.11.8"

assemblyJarName in assembly := { s"${name.value}.jar" }

libraryDependencies ++= Seq(

  // sbt from http://akka.io/docs/
  "com.typesafe.akka" %% "akka-http-core" % "10.0.5",
  "com.typesafe.akka" %% "akka-http" % "10.0.5",
  "com.typesafe.akka" %% "akka-http-testkit" % "10.0.5",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.0.5",
  "com.typesafe.akka" %% "akka-http-jackson" % "10.0.5",
  "com.typesafe.akka" %% "akka-http-xml" % "10.0.5",

  // Slick
  "com.typesafe.slick" %% "slick" % "3.1.1",
  "org.slf4j" % "slf4j-nop" % "1.7.21",
  "com.h2database" % "h2" % "1.4.191",

  // Datetime
  "com.github.nscala-time" %% "nscala-time" % "2.18.0"
)

enablePlugins(JavaAppPackaging)