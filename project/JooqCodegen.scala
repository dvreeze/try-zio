
import sbt._

import org.jooq._
import org.jooq.codegen._
import org.jooq.meta.jaxb
import org.jooq.meta.jaxb._

object JooqCodegen {

  def makeConfiguration(jdbcConnectionUrl: String, user: String, password: String): jaxb.Configuration = {
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
              .withPackageName("eu.cdevreeze.tryzio.jooq.generated")
              .withDirectory("target/generated-sources/jooq")
          }
      }
  }

  def getMySqlConnectionUrl(databaseName: String): String = {
    s"jdbc:mysql://localhost:3306/$databaseName"
  }

  private val databaseName = "wordpress"
  private val user = "root"
  private val password = "root" // Not exactly secure

  def makeConfiguration(): jaxb.Configuration = makeConfiguration(getMySqlConnectionUrl(databaseName), user, password)

  def generate(): Unit = GenerationTool.generate(makeConfiguration())
}
