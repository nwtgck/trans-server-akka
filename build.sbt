import sbtassembly.AssemblyPlugin.autoImport.assemblyJarName

name := "trans-server-akka"

version := "1.19.9-SNAPSHOT"

scalaVersion := "2.11.12"

val akkaHttpVersion = "10.0.15"

lazy val root = (project in file(".")).
  enablePlugins(BuildInfoPlugin, JavaAppPackaging).
  settings(
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "io.github.nwtgck.trans_server",

    // Skip test when sbt assembly
    // (from: http://www.shigemk2.com/entry/scala_aseembly_part2)
    test in assembly := {},

    // Change jar name by "sbt assembly"
    assemblyJarName in assembly := { s"${name.value}.jar" },

    unmanagedResourceDirectories in Compile += baseDirectory.value / "trans-client-web",

    // Set name of target/universal/<name>.zip
    // (from: https://stackoverflow.com/a/40765824/2885946)
    packageName in Universal := s"${name.value}",

    libraryDependencies ++= Seq(
      // sbt from http://akka.io/docs/
      "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,

      // Slick
      "com.typesafe.slick" %% "slick" % "3.1.1",
      "org.slf4j" % "slf4j-nop" % "1.7.26",
      "com.h2database" % "h2" % "1.4.199",

      // scopt
      "com.github.scopt" %% "scopt" % "3.6.0",

      // ScalaTest
      "org.scalatest" %% "scalatest" % "3.0.7" % Test
    )
  )
