import sbtrelease.ReleaseStateTransformations._

name := "zio-redis"

lazy val `zio-redis` = (project in file(".")).aggregate(core, test)

lazy val core = project in file("core")

lazy val test = (project in file("test")).dependsOn(core)

Common.settings

publishArtifact := false

releaseCrossBuild := true // true if you cross-build the project for multiple Scala versions

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  releaseStepCommand("publishSigned"),
  setNextVersion,
  commitNextVersion,
  releaseStepCommand("sonatypeReleaseAll"),
  pushChanges
)
