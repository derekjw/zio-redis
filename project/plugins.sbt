addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.1.2-1")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "2.3")
addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.9")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.6.0")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.2.1")
addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.3")

resolvers += Resolver.sonatypeRepo("snapshots")
addSbtPlugin("com.github.vovapolu" % "zio-shield" % "0.1.0-SNAPSHOT")