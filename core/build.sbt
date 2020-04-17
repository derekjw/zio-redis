name := "zio-redis-core"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % "1.0.0-RC18-2",
  "dev.zio" %% "zio-streams" % "1.0.0-RC18-2",
  "dev.zio" %% "zio-nio" % "1.0.0-RC6",
  "dev.zio" %% "zio-logging" % "0.2.7",
)

Common.settings
