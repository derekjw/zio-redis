name := "zio-redis-core"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % "1.0.0-RC17",
  "dev.zio" %% "zio-streams" % "1.0.0-RC17",
  "dev.zio" %% "zio-nio" % "0.4.0"
)

Common.settings
