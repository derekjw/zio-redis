import sbtrelease.ReleaseStateTransformations._

name := "zio-redis"

lazy val `zio-redis` = (project in file(".")).aggregate(core)

lazy val core = project in file("core")

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
