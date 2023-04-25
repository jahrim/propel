import scala.scalanative.build._

ThisBuild / scalaVersion := "3.2.2"

ThisBuild / scalacOptions ++= Seq("-feature", "-deprecation", "-unchecked", "-Yexplicit-nulls")

ThisBuild / nativeConfig ~= { _.withLTO(LTO.thin).withMode(Mode.releaseFast) }

ThisBuild / libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.15" % Test

ThisBuild / Test / logBuffered := false

ThisBuild / Test / parallelExecution := false

lazy val propelCrossProject = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("."))
  .jsConfigure(_.withId("propelJS"))
  .jvmConfigure(_.withId("propelJVM"))
  .nativeConfigure(_.withId("propelNative"))
  .jvmSettings(
    libraryDependencies += "org.scala-js" %% "scalajs-stubs" % "1.1.0" % "provided",
    scalaVersion := "3.3.0-RC3")
  .nativeSettings(
    libraryDependencies += "org.scala-js" %% "scalajs-stubs" % "1.1.0" % "provided",
    scalaVersion := "3.2.2",
    Compile / mainClass := Some("propel.check"),
    Compile / nativeLink / artifactPath ~= { file => file.getParentFile / file.getName.replace("propelcrossproject-out", "propel") })

lazy val propelJS = propelCrossProject.js
lazy val propelJVM = propelCrossProject.jvm
lazy val propelNative = propelCrossProject.native

lazy val propel = project
  .in(file("."))
  .aggregate(propelJS, propelJVM, propelNative)
  .settings(
    compile / skip := true,
    compile / aggregate := false,
    testOnly / aggregate := false,
    test / aggregate := false,
    Test / compile / skip := true,
    Test / compile / aggregate := false,
    Test / testOnly / aggregate := false,
    Test / test / aggregate := false)

run := (propelJVM / Compile / run).evaluated
runMain := (propelJVM / Compile / runMain).evaluated
compile := (propelJVM / Compile / compile).value
testOnly := (propelJVM / Test / testOnly).evaluated
test := (propelJVM / Test / test).value
Test / run := (propelJVM / Test / run).evaluated
Test / runMain := (propelJVM / Test / runMain).evaluated
Test / compile := (propelJVM / Test / compile).value
Test / testOnly := (propelJVM / Test / testOnly).evaluated
Test / test := (propelJVM / Test / test).value
