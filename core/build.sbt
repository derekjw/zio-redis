name := "zio-redis-core"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % "1.0.0-RC18-1",
  "dev.zio" %% "zio-streams" % "1.0.0-RC18-1",
  "dev.zio" %% "zio-nio" % "1.0.0-RC4"
)

Common.settings
