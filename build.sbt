
val scalaVer = "3.1.1"
val crossScalaVer = Seq(scalaVer)

ThisBuild / description  := "Trying out ZIO"
ThisBuild / organization := "eu.cdevreeze.tryzio"
ThisBuild / version      := "0.1.0-SNAPSHOT"

ThisBuild / versionScheme := Some("strict")

ThisBuild / scalaVersion       := scalaVer
ThisBuild / crossScalaVersions := crossScalaVer

ThisBuild / semanticdbEnabled := false // do not enable SemanticDB

ThisBuild / scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature")

ThisBuild / publishMavenStyle := true

ThisBuild / publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value) {
    Some("snapshots" at nexus + "content/repositories/snapshots")
  } else {
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
  }
}

ThisBuild / pomExtra := pomData
ThisBuild / pomIncludeRepository := { _ => false }

ThisBuild / libraryDependencies += "dev.zio" %% "zio" % "2.0.0-RC2"
ThisBuild / libraryDependencies += "dev.zio" %% "zio-streams" % "2.0.0-RC2"
// ThisBuild / libraryDependencies += "dev.zio" %% "zio-managed" % "2.0.0-RC3" // TODO Remove once no longer needed

ThisBuild / libraryDependencies += "io.d11" %% "zhttp" % "2.0.0-RC4"
ThisBuild / libraryDependencies += "io.d11" %% "zhttp-test" % "2.0.0-RC4" % Test

// Requires Java 11 at minimum
ThisBuild / libraryDependencies += "org.eclipse.jetty" % "jetty-servlet" % "11.0.8"
ThisBuild / libraryDependencies += "org.eclipse.jetty" % "jetty-server" % "11.0.8"
ThisBuild / libraryDependencies += "org.eclipse.jetty" % "jetty-webapp" % "11.0.8"

lazy val root = project.in(file("."))
  .settings(
    name                 := "tryzio",
    publish              := {},
    publishLocal         := {},
    publishArtifact      := false,
    Keys.`package`       := file(""))

lazy val pomData =
  <url>https://github.com/dvreeze/try-zio</url>
  <licenses>
    <license>
      <name>Apache License, Version 2.0</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
      <distribution>repo</distribution>
      <comments>Try-zio is licensed under Apache License, Version 2.0</comments>
    </license>
  </licenses>
  <scm>
    <connection>scm:git:git@github.com:dvreeze/try-zio.git</connection>
    <url>https://github.com/dvreeze/try-zio.git</url>
    <developerConnection>scm:git:git@github.com:dvreeze/try-zio.git</developerConnection>
  </scm>
  <developers>
    <developer>
      <id>dvreeze</id>
      <name>Chris de Vreeze</name>
      <email>chris.de.vreeze@caiway.net</email>
    </developer>
  </developers>

