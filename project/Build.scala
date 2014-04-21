import sbt._
import sbt.Keys._
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import com.typesafe.sbt.osgi.SbtOsgi._
import com.typesafe.sbt.osgi.OsgiKeys._

object Build extends sbt.Build {

  lazy val proj = Project(
    "spray-websocket",
    file("."),
    settings = commonSettings ++ Seq(
      libraryDependencies ++= Dependencies.all))

  def commonSettings = Defaults.defaultSettings ++
    osgiSettings ++
    formatSettings ++
    Seq(
      organization := "com.wandoulabs",
      version := "0.1.1-SNAPSHOT",
      scalaVersion := "2.10.3",
      scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
      publishMavenStyle := true,
      exportPackage := Seq("spray.can.websocket.*"),
      resolvers ++= Seq(
        "typesafe repo" at "http://repo.typesafe.com/typesafe/releases/",
        "spray" at "http://repo.spray.io",
        "spray nightly" at "http://nightlies.spray.io/"),
      publishTo := {
        val nexus = "https://oss.sonatype.org/"
        if (version.value.trim.endsWith("SNAPSHOT"))
          Some("snapshots" at nexus + "content/repositories/snapshots")
        else
          Some("releases"  at nexus + "service/local/staging/deploy/maven2")
      },
      credentials += Credentials(Path.userHome / ".ivy2" / ".wandou-credentials"))

  lazy val formatSettings = SbtScalariform.scalariformSettings ++ Seq(
    ScalariformKeys.preferences in Compile := formattingPreferences,
    ScalariformKeys.preferences in Test := formattingPreferences)

  import scalariform.formatter.preferences._
  def formattingPreferences =
    FormattingPreferences()
      .setPreference(RewriteArrowSymbols, false)
      .setPreference(AlignParameters, true)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(DoubleIndentClassDeclaration, true)
      .setPreference(IndentSpaces, 2)
  
}

object Dependencies {
  val SPRAY_VERSION = "1.3.0"
  val AKKA_VERSION = "2.3.2"

  val spray_can = "io.spray" % "spray-can" % SPRAY_VERSION
  val spray_testkit = "io.spray" % "spray-testkit" % SPRAY_VERSION % "test"
  val akka_actor = "com.typesafe.akka" %% "akka-actor" % AKKA_VERSION
  val akka_testkit = "com.typesafe.akka" %% "akka-testkit" % AKKA_VERSION % "test"
  val scalatest = "org.scalatest" %% "scalatest" % "2.0" % "test"
  val specs2 = "org.specs2" %% "specs2" % "2.2.3" % "test"

  val all = Seq(spray_can, akka_actor, scalatest, akka_testkit, specs2, spray_testkit)

}
