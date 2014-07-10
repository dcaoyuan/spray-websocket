import sbt._
import sbt.Keys._
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import com.typesafe.sbt.osgi.SbtOsgi._
import com.typesafe.sbt.osgi.OsgiKeys._

object Build extends sbt.Build {

  lazy val root = Project("spray-websocket-root", file("."))
    .aggregate(examples, websocket)
    .settings(basicSettings: _*)
    .settings(noPublishing: _*)

  lazy val websocket = Project("spray-websocket", file("spray-websocket"))
    .settings(defaultOsgiSettings: _*)
    .settings(
      exportPackage := Seq("spray.*"),
      privatePackage := Nil)
    .settings(basicSettings: _*)
    .settings(formatSettings: _*)
    .settings(releaseSettings: _*)
    .settings(libraryDependencies ++= Dependencies.all)

  lazy val examples = Project("spray-websocket-examples", file("examples"))
    .aggregate(websocketSimple)
    .settings(exampleSettings: _*)

  lazy val websocketSimple = Project("spray-websocket-examples-simple", file("examples/spray-websocket-simple"))
    .dependsOn(websocket)
    .settings(formatSettings: _*)
    .settings(exampleSettings: _*)

  lazy val basicSettings = Seq(
    organization := "com.wandoulabs.akka",
    version := "0.1.2-RC3",
    scalaVersion := "2.10.3",
    scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
    resolvers ++= Seq(
      "typesafe repo" at "http://repo.typesafe.com/typesafe/releases/",
      "spray" at "http://repo.spray.io",
      "spray nightly" at "http://nightlies.spray.io/"))

  lazy val exampleSettings = basicSettings ++ noPublishing

  lazy val releaseSettings = Seq(
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (version.value.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
    },
    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomIncludeRepository := { (repo: MavenRepository) => false },
    pomExtra := (
      <url>https://github.com/wandoulabs/spray-websocket</url>
      <licenses>
        <license>
          <name>The Apache Software License, Version 2.0</name>
          <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
          <distribution>repo</distribution>
        </license>
      </licenses>
      <scm>
        <url>git@github.com:wandoulabs/spray-websocket.git</url>
        <connection>scm:git:git@github.com:wandoulabs/spray-websocket.git</connection>
      </scm>
      <developers>
        <developer>
          <id>dcaoyuan</id>
          <name>Caoyuan DENG</name>
          <email>dcaoyuan@gmail.com</email>
        </developer>
        <developer>
          <id>cowboy129</id>
          <name>Xingrun CHEN</name>
          <email>cowboy129@gmail.com</email>
        </developer>
      </developers>))

  lazy val noPublishing = Seq(
    publish := (),
    publishLocal := (),
    // required until these tickets are closed https://github.com/sbt/sbt-pgp/issues/42,
    // https://github.com/sbt/sbt-pgp/issues/36
    publishTo := None)

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
  val SPRAY_VERSION = "1.3.2-20140428"
  val AKKA_VERSION = "2.3.2"

  val spray_can = "io.spray" % "spray-can" % SPRAY_VERSION
  val spray_routing = "io.spray" % "spray-routing" % SPRAY_VERSION
  val spray_testkit = "io.spray" % "spray-testkit" % SPRAY_VERSION % "test"
  val akka_actor = "com.typesafe.akka" %% "akka-actor" % AKKA_VERSION
  val akka_testkit = "com.typesafe.akka" %% "akka-testkit" % AKKA_VERSION % "test"
  val scalatest = "org.scalatest" %% "scalatest" % "2.1.3" % "test"
  val specs2 = "org.specs2" %% "specs2" % "2.3.11" % "test"

  val all = Seq(spray_can, spray_routing, akka_actor, scalatest, akka_testkit, specs2, spray_testkit)

}
