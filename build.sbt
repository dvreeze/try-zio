
val scalaVer = "3.2.2"
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

val zioVersion = "2.0.13"
val zioJsonVersion = "0.5.0"
val zioHttpVersion = "0.0.5"
val zioConfigVersion = "4.0.0-RC14"
val testContainersVersion = "1.18.0"
val jooqVersion = "3.16.18" // Works with Java 11

ThisBuild / libraryDependencies += "dev.zio" %% "zio" % zioVersion
ThisBuild / libraryDependencies += "dev.zio" %% "zio-streams" % zioVersion
ThisBuild / libraryDependencies += "dev.zio" %% "zio-test" % zioVersion % Test
ThisBuild / libraryDependencies += "dev.zio" %% "zio-test-sbt" % zioVersion % Test
ThisBuild / libraryDependencies += "dev.zio" %% "zio-test-magnolia" % zioVersion % Test
ThisBuild / libraryDependencies += "dev.zio" %% "zio-test-junit" % zioVersion % Test

ThisBuild / libraryDependencies += "dev.zio" %% "zio-config" % zioConfigVersion
ThisBuild / libraryDependencies += "dev.zio" %% "zio-jdbc" % "0.0.2"

ThisBuild / libraryDependencies += "dev.zio" %% "zio-json" % zioJsonVersion

ThisBuild / libraryDependencies += "dev.zio" %% "zio-http" % zioHttpVersion

ThisBuild / libraryDependencies += "javax.servlet" % "servlet-api" % "3.0-alpha-1" % Provided

ThisBuild / libraryDependencies += "org.apache.tomcat.embed" % "tomcat-embed-core" % "10.1.8"

ThisBuild / libraryDependencies += "org.testcontainers" % "mysql" % testContainersVersion % Test
ThisBuild / libraryDependencies += "mysql" % "mysql-connector-java" % "8.0.33"
ThisBuild / libraryDependencies += "com.zaxxer" % "HikariCP" % "5.0.1" // requires Java 11+

ThisBuild / libraryDependencies += "org.jooq" % "jooq" % jooqVersion
ThisBuild / libraryDependencies += "org.jooq" % "jooq-meta" % jooqVersion
ThisBuild / libraryDependencies += "org.jooq" % "jooq-codegen" % jooqVersion
// Used by JOOQ
ThisBuild / libraryDependencies += "org.jetbrains" % "annotations" % "24.0.1"

ThisBuild / libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.4.7"

ThisBuild / testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

Compile / sourceGenerators += Def.task {
  JooqCodegen.generateJavaFiles((Compile / sourceManaged).value)
}.taskValue

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
