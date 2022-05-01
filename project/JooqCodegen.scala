
import sbt._

import java.io.File
import org.jooq._
import org.jooq.codegen._
import org.jooq.meta.jaxb
import org.jooq.meta.jaxb._

object JooqCodegen {

  private val targetPackageName = "eu.cdevreeze.tryzio.jooq.generated"
  private val managedSourceSubDir = "jooq"

  def makeConfiguration(managedSourceDir: File, jdbcConnectionUrl: String, user: String, password: String): jaxb.Configuration = {
    val targetDirectory: File = new File(managedSourceDir, managedSourceSubDir)
    new jaxb.Configuration()
      .withJdbc {
        new Jdbc()
          .withDriver("com.mysql.cj.jdbc.Driver")
          .withUrl(jdbcConnectionUrl)
          .withUser(user)
          .withPassword(password)
      }
      .withGenerator {
        new jaxb.Generator()
          .withDatabase {
            new Database()
              .withName("org.jooq.meta.mysql.MySQLDatabase")
              .withIncludes(".*")
              .withExcludes("")
              .withSchemata(
                new SchemaMappingType().withInputSchema("wordpress"),
                new SchemaMappingType().withInputSchema("mysql"),
              )
          }
          .withGenerate(new Generate())
          .withTarget {
            new Target()
              .withPackageName(targetPackageName)
              .withDirectory(targetDirectory.toString)
          }
      }
  }

  def getMySqlConnectionUrl(databaseName: String): String = {
    s"jdbc:mysql://localhost:3306/$databaseName"
  }

  private val databaseName = "wordpress"
  private val user = "root"
  private val password = "root" // Not exactly secure

  def makeConfiguration(managedSourceDir: File): jaxb.Configuration = {
    makeConfiguration(managedSourceDir, getMySqlConnectionUrl(databaseName), user, password)
  }

  def generateJavaFiles(managedSourceDir: File): Seq[File] = {
    GenerationTool.generate(makeConfiguration(managedSourceDir))

    val targetDir: File = new File(managedSourceDir, managedSourceSubDir)
    findJavaFiles(targetDir)
  }

  private def findJavaFiles(dir: File): Seq[File] = {
    dir.listFiles().toSeq.flatMap { f =>
      f match {
        case d: File if d.isDirectory =>
          // Recursive call
          findJavaFiles(d)
        case f: File if f.isFile && f.getName.endsWith(".java") =>
          Seq(f)
      }
    }
  }
}
