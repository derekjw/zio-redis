name := "zio-redis-test"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio-test" % "1.0.0-RC18-2",
  "dev.zio" %% "zio-test-sbt" % "1.0.0-RC18-2" % Test,
  "dev.zio" %% "zio-macros-core" % "0.6.2" % Test
)

testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))

Common.settings
