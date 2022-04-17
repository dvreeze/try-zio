
val scalaVer = "3.1.2"
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

val zioVersion = "2.0.0-RC4"
val zioHttpVersion = "2.0.0-RC6"
val testContainersVersion = "1.16.3"

ThisBuild / libraryDependencies += "dev.zio" %% "zio" % zioVersion
ThisBuild / libraryDependencies += "dev.zio" %% "zio-streams" % zioVersion
ThisBuild / libraryDependencies += "dev.zio" %% "zio-test" % zioVersion % Test
ThisBuild / libraryDependencies += "dev.zio" %% "zio-test-sbt" % zioVersion % Test
ThisBuild / libraryDependencies += "dev.zio" %% "zio-test-magnolia" % zioVersion % Test
ThisBuild / libraryDependencies += "dev.zio" %% "zio-test-junit" % zioVersion % Test

ThisBuild / libraryDependencies += "io.d11" %% "zhttp" % zioHttpVersion
ThisBuild / libraryDependencies += "io.d11" %% "zhttp-test" % zioHttpVersion % Test

ThisBuild / libraryDependencies += "javax.servlet" % "servlet-api" % "3.0-alpha-1" % Provided

ThisBuild / libraryDependencies += "org.apache.tomcat.embed" % "tomcat-embed-core" % "10.1.0-M14"

ThisBuild / libraryDependencies += "org.testcontainers" % "mysql" % testContainersVersion % Test
ThisBuild / libraryDependencies += "mysql" % "mysql-connector-java" % "8.0.28" % Test
ThisBuild / libraryDependencies += "com.zaxxer" % "HikariCP" % "5.0.1" % Test // requires Java 11+

ThisBuild / libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.11" % Test

ThisBuild / testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

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

