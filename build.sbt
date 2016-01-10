name := "sniffl"
organization := "ph.samson"
version := "1.0.0-SNAPSHOT"
scalaVersion := "2.12.1"

scalacOptions ++= Seq(
  "-feature"
)

libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-classic" % "1.1.8",
  "com.flickr4java" % "flickr4java" % "2.16",
  "com.google.guava" % "guava" % "20.0",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",
  "com.sun.mail" % "javax.mail" % "1.5.6",
  "joda-time" % "joda-time" % "2.9.6"
)
