name := "zio-redis-test"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio-test" % "1.0.0-RC16",
  "dev.zio" %% "zio-test-sbt" % "1.0.0-RC16" % Test,
  "dev.zio" %% "zio-macros-core" % "0.5.0" % Test
)

testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))

Common.settings
