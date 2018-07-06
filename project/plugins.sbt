logLevel := Level.Warn

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.2.0")

// To get version in build.sbt
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.6.1")

// For coverage
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1")

// For coveralls
addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.2.5")
