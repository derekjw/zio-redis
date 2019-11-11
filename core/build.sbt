name := "zio-redis-core"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio-nio" % "0.3.1",
  "dev.zio" %% "zio-macros-core" % "0.5.0" % Test
)

Common.settings
