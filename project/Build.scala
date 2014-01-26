import sbt._
import Keys._
import sbt.inc.Analysis
import sbtassembly.Plugin.{PathList, MergeStrategy, AssemblyKeys}

object AstracJvmBuild extends Build {
  // Akka
  val akkaActor = "com.typesafe.akka" %% "akka-actor" % "2.2.3"
  val akkaSlf4j = "com.typesafe.akka" %% "akka-slf4j" % "2.2.3"

  // Utilities
  val config = "com.typesafe" % "config" % "1.0.2"
  val rxjava = "com.netflix.rxjava" % "rxjava-scala" % "0.15.1"
  val specs2 = "org.specs2" %% "specs2" % "2.2.3" % "test"
  val jodaTime = "joda-time" % "joda-time" % "2.3"
  val libphonenumber = "com.googlecode.libphonenumber" % "libphonenumber" % "5.9"
  val scalaCsv = "com.github.tototoshi" %% "scala-csv" % "1.0.0-SNAPSHOT"

  // DB
  val c3p0 =  "com.mchange" % "c3p0" % "0.9.2.1"
  val mysqlConnector = "mysql" % "mysql-connector-java" % "5.1.27"
  val slick = "com.typesafe.slick" %% "slick" % "1.0.1"
  val slickJodaMapper = "com.github.tototoshi" % "slick-joda-mapper_2.10" % "0.4.0"

  // Guice
  val scalaGuice = "net.codingwell" % "scala-guice_2.10" % "4.0.0-beta"

  // Logging
  val logbackClassic = "ch.qos.logback" % "logback-classic" % "1.0.12"
  val logbackCore = "ch.qos.logback" % "logback-core" % "1.0.12"
  val slf4jApi = "org.slf4j" % "slf4j-api" % "1.6.4"

  // Spray
  val sprayCan = "io.spray" % "spray-can" % "1.2.0"
  val sprayJson = "io.spray" % "spray-json_2.10" % "1.2.5"
  val sprayRouting = "io.spray" % "spray-routing" % "1.2.0"
  val sprayTestkit = "io.spray" % "spray-testkit" % "1.2.0"
  val json4s = "org.json4s" %% "json4s-native" % "3.2.6"

  lazy val baseSettings = Project.defaultSettings ++ sbtassembly.Plugin.assemblySettings ++ Seq(
    scalaVersion := "2.10.3",
    sbtVersion := "0.13.1",
    scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
    resolvers ++= Seq(
      "spray repo" at "http://repo.spray.io",
      "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"),
    libraryDependencies ++= Seq(
      config,
      jodaTime,
      specs2,
      logbackClassic,
      logbackCore,
      slf4jApi
    ))

  lazy val slickSettings = baseSettings ++ Seq(
    libraryDependencies ++= Seq(
      slick,
      slickJodaMapper,
      c3p0
    )
  )

  lazy val spraySettings = baseSettings ++ Seq(
    libraryDependencies ++= Seq(
      akkaActor,
      akkaSlf4j,
      sprayCan,
      sprayRouting,
      sprayJson
    )
  )

  lazy val astracJvm = Project(id = "astrac-jvm", base = file(".")) aggregate(astracEtl)

  val gruntBuildTask = taskKey[Unit]("Builds a grunt project")

  val gruntSettings = gruntBuildTask := gruntBuild((classDirectory in astracEtl in Compile).value / "ui")

  def gruntBuild(rootDir: File) = {
    val exitCode = Process("grunt" :: "build" :: Nil, rootDir) !

    if (exitCode != 0) {
      sys.error(s"Cannot build grunt project in ${rootDir.absolutePath}")
    }
  }

  val gruntCompileSettings = Seq(gruntSettings, compile <<= (compile in astracEtl in Compile) dependsOn gruntBuildTask)

  lazy val astracEtl: Project =
    Project(id = "etl", base = file("./etl"), settings = spraySettings)
}
