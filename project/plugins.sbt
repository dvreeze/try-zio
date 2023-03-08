
// For a list of well-known plugins, see https://www.scala-sbt.org/1.x/docs/Community-Plugins.html.

// See https://github.com/scalameta/sbt-scalafmt
// Tasks: scalafmt
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.0")

// See https://scalacenter.github.io/scalafix/docs/users/installation.html
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.9.34")

val jooqVersion = "3.16.11"

ThisBuild / libraryDependencies += "org.jooq" % "jooq" % jooqVersion
ThisBuild / libraryDependencies += "org.jooq" % "jooq-meta" % jooqVersion
ThisBuild / libraryDependencies += "org.jooq" % "jooq-codegen" % jooqVersion
// Used by JOOQ
ThisBuild / libraryDependencies += "org.jetbrains" % "annotations" % "24.0.1"

ThisBuild / libraryDependencies += "mysql" % "mysql-connector-java" % "8.0.32"
