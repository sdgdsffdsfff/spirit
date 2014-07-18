import com.github.retronym.SbtOneJar._

oneJarSettings

name := "spirit"

organization := "com.qianmi.bugatti"

version := "1.0-SNAPSHOT"

scalaVersion := "2.10.3"

libraryDependencies ++= {
  val sprayV = "1.3.1"
  val akkaV = "2.3.4"
  Seq(
    "com.typesafe.akka" %% "akka-remote" % akkaV,
    "com.typesafe.akka" %% "akka-slf4j" % akkaV,
    "io.spray" %% "spray-can" % sprayV,
    "com.typesafe.play" %% "play-json" % "2.2.3",
    "ch.qos.logback" % "logback-classic" % "1.1.2"
//    "io.spray" % "spray-routing" % sprayV
  )
}