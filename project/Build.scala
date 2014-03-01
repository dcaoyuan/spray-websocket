import sbt._
import sbt.Keys._

object Build extends sbt.Build {

  lazy val proj = Project(
    "spray-websocket",
    file("."),
    settings = commonSettings ++ Seq(
      libraryDependencies ++= Dependencies.all))

  def commonSettings = Defaults.defaultSettings ++
    Seq(
      organization := "com.wandoulabs",
      version := "0.1",
      scalaVersion := "2.10.3",
      scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
      publishTo <<= isSnapshot { isSnapshot =>
        val id = if (isSnapshot) "snapshots" else "releases"
        val uri = "http://repo.scala-sbt.org/scalasbt/sbt-plugin-" + id
        Some(Resolver.url("sbt-plugin-" + id, url(uri))(Resolver.ivyStylePatterns))
      },
      publishMavenStyle := false,
      resolvers ++= Seq(
        "typesafe repo" at "http://repo.typesafe.com/typesafe/releases/",
        "spray" at "http://repo.spray.io",
        "spray nightly" at "http://nightlies.spray.io/"))
}

object Dependencies {
  val spray_can = "io.spray" % "spray-can" % "1.3-RC4"
  val spray_testkit = "io.spray" % "spray-testkit" % "1.3-RC4" % "test"
  val akka_actor = "com.typesafe.akka" %% "akka-actor" % "2.3.0-RC4"
  val akka_testkit = "com.typesafe.akka" %% "akka-testkit" % "2.3.0-RC4" % "test"
  val scalatest = "org.scalatest" %% "scalatest" % "2.0" % "test"
  val specs2 = "org.specs2" %% "specs2" % "2.2.3"

  val all = Seq(spray_can, akka_actor, scalatest, akka_testkit, specs2, spray_testkit)

}
