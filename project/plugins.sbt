logLevel := Level.Warn

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.15")

// To get version in build.sbt
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.7.0")

// For coverage
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1")

// For coveralls
addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.2.7")
