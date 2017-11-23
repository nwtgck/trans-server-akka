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
  "com.typesafe.akka" %% "akka-http-xml" % "10.0.5"
)

enablePlugins(JavaAppPackaging)