/*
 * Copyright 2022-2022 Chris de Vreeze
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.cdevreeze.tryzio.jdbc

import java.sql.Connection
import java.sql.PreparedStatement

import scala.util.chaining.*

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import eu.cdevreeze.tryzio.jdbc.ConnectionWork.*
import eu.cdevreeze.tryzio.jdbc.JdbcSupport.Argument.*
import eu.cdevreeze.tryzio.jooq.generated.mysql.Tables.*
import javax.sql.DataSource
import org.jooq.*
import org.jooq.impl.DSL
import org.jooq.impl.DSL.`val`
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.primaryKey
import org.jooq.impl.DSL.table
import org.jooq.impl.SQLDataType.*
import zio.*
import zio.Console.printLine
import zio.test.Assertion.*
import zio.test.ZIOSpecDefault
import zio.test.assert

/**
 * JDBC support test, against MySQL Docker container. Currently the test runs only against an already running MySQL Docker container, and
 * not against a testcontainers Docker instance.
 *
 * Before running, start the MySQL Docker container: "sudo docker run --name some-mysql -e MYSQL_ROOT_PASSWORD=root -p 3306:3306 -d
 * mysql:latest"
 *
 * To peek into that MySQL Docker container, use the following command, followed by a "mysql" session: "sudo docker exec -it some-mysql
 * bash"
 *
 * @author
 *   Chris de Vreeze
 */
object JdbcSupportTest extends ZIOSpecDefault:

  import JdbcSupport.*

  private val dsLayer: TaskLayer[DataSource] = ZLayer.fromZIO(getDataSource())

  private val getCp: Task[ZConnectionPool] =
    ZIO
      .service[ZConnectionPool]
      .provideLayer(dsLayer >>> ZConnectionPoolFromDataSource.layer)

  private def makeDsl(): DSLContext = DSL.using(SQLDialect.MYSQL)

  private final case class User(host: String, user: String)

  private final case class Timezone(id: Int, name: String, useLeapSeconds: Boolean)

  def spec = suite("ZIO-based JDBC Support test") {
    // Probably wasteful to start connection pool on each test.

    List(
      test("Querying for users succeeds") {
        for {
          result <- getUsers()
            .tap(res => printLine(s"Users: $res"))
        } yield assert(result.map(_.host).distinct)(equalTo(Seq("%", "localhost")))
      },
      test("Querying for users the hard way succeeds") {
        for {
          result <- getDataSource()
            .flatMap(ds => getUsersTheHardWay(ds))
            .tap(res => printLine(s"Users: $res"))
        } yield assert(result.map(_.host).distinct)(equalTo(Seq("%", "localhost")))
      },
      test("Querying for users the verbose way succeeds") {
        for {
          result <- getDataSource()
            .flatMap(ds => getUsersTheVerboseWay(ds))
            .tap(res => printLine(s"Users: $res"))
        } yield assert(result.map(_.host).distinct)(equalTo(Seq("%", "localhost")))
      },
      test("Querying multiple times simultaneously for users succeeds") {
        for {
          multipleResults <- getUsers()
            .pipe(getDs => ZIO.foreachPar(0.until(5))(_ => getDs))
        } yield assert(multipleResults.flatten.map(_.host).distinct)(equalTo(Seq("%", "localhost")))
      },
      test("Querying for timezones succeeds") {
        for {
          result <- getSomeTimezones("Europe/%")
            .tap(res => printLine(s"Timezones: $res"))
        } yield {
          assert(result.map(_.name))(contains("Europe/Lisbon")) &&
          assert(result.map(_.name))(contains("Europe/Berlin")) &&
          assert(result.map(_.name))(contains("Europe/Moscow")) &&
          assert(result.map(_.name))(contains("Europe/Amsterdam"))
        }
      },
      test("Querying multiple times simultaneously for timezones succeeds") {
        for {
          multipleResults <- getSomeTimezones("Europe/%")
            .pipe(getDs => ZIO.foreachPar(0.until(5))(_ => getDs))
        } yield {
          assert(multipleResults.flatten.map(_.name))(contains("Europe/Lisbon")) &&
          assert(multipleResults.flatten.map(_.name))(contains("Europe/Berlin")) &&
          assert(multipleResults.flatten.map(_.name))(contains("Europe/Moscow")) &&
          assert(multipleResults.flatten.map(_.name))(contains("Europe/Amsterdam"))
        }
      },
      test("Inserting users into the second user table and querying them succeeds") {
        for {
          ds <- getDataSource()
          _ <- createSecondUserTable(ds)
          _ <- copyUsers(ds)
          users <- getUsersFromSecondUserTable(ds)
          _ <- dropSecondUserTable(ds)
        } yield {
          assert(users)(contains(User("localhost", "root"))) &&
          assert(users)(contains(User("%", "root")))
        }
      }
    )
  }

  private def getUsers(): Task[Seq[User]] =
    for {
      dsl <- ZIO.attempt(makeDsl())
      sqlQuery <- ZIO.attempt(dsl.select(USER.HOST, USER.USER_).from(USER))
      cp <- getCp
      result <- cp.txReadCommitted(queryForSeq(sqlQuery.getSQL, Seq.empty, { (rs, _) => User(rs.getString(1), rs.getString(2)) }))
    } yield result
  end getUsers

  private def getUsersTheHardWay(ds: DataSource): Task[Seq[User]] =
    def createPreparedStatement(conn: Connection): Task[PreparedStatement] =
      ZIO.attempt {
        val dsl = makeDsl()
        conn.prepareStatement(dsl.select(USER.HOST, USER.USER_).from(USER).getSQL)
      }

    using(ds)
      .queryForSingleResult(createPreparedStatement) { rs =>
        // This unsafe code is wrapped in a Task by function queryForSingleColumn
        Iterator.from(1).takeWhile(_ => rs.next).map(_ => User(rs.getString(1), rs.getString(2))).toSeq
      }
  end getUsersTheHardWay

  private def getUsersTheVerboseWay(ds: DataSource): Task[Seq[User]] =
    ZIO.attempt(makeDsl()).flatMap { dsl =>
      using(ds)
        .execute { conn =>
          using(conn)
            .query(dsl.select(USER.HOST, USER.USER_).from(USER).getSQL, Seq.empty) { (rs, _) =>
              User(rs.getString(1), rs.getString(2))
            }
        }
    }
  end getUsersTheVerboseWay

  private def getSomeTimezones(timezoneLikeString: String): Task[Seq[Timezone]] =
    val sqlQueryTask: Task[Query] = ZIO.attempt {
      val dsl = makeDsl()
      dsl
        .select(TIME_ZONE.TIME_ZONE_ID, TIME_ZONE_NAME.NAME, TIME_ZONE.USE_LEAP_SECONDS)
        .from(TIME_ZONE)
        .join(TIME_ZONE_NAME)
        .on(TIME_ZONE.TIME_ZONE_ID.equal(TIME_ZONE_NAME.TIME_ZONE_ID))
        .where(TIME_ZONE_NAME.NAME.like(`val`("dummyNameArg")))
    }

    for {
      sqlQuery <- sqlQueryTask
      cp <- getCp
      result <- cp.txReadCommitted(
        queryForSeq(
          sqlQuery.getSQL,
          Seq(StringArg(timezoneLikeString)),
          { (rs, _) =>
            Timezone(rs.getInt(1), rs.getString(2), rs.getString(3).pipe(_ == "Y"))
          }
        )
      )
    } yield result
  end getSomeTimezones

  private def createSecondUserTable(ds: DataSource): Task[Unit] =
    val sqlStatTask: Task[CreateTableConstraintStep] = ZIO.attempt {
      val dsl = makeDsl()
      dsl
        .createTableIfNotExists(table("user_summary"))
        .column(field("name", VARCHAR), VARCHAR(255).notNull)
        .column(field("host", VARCHAR), VARCHAR(255).notNull)
        .constraint(primaryKey(field("name", VARCHAR), field("host", VARCHAR)))
    }

    for {
      sqlStat <- sqlStatTask
      _ <- using(ds, IsolationLevel.ReadCommitted)
        .update(sqlStat.getSQL, Seq.empty)
        .unit
    } yield ()
  end createSecondUserTable

  private def copyUsers(ds: DataSource): Task[Unit] =
    def sql1Task(dsl: DSLContext): Task[Delete[_]] =
      ZIO.attempt { dsl.deleteFrom(table("user_summary")) }
    def sql2Task(dsl: DSLContext): Task[Insert[_]] =
      ZIO.attempt {
        dsl
          .insertInto(table("user_summary"))
          .columns(field("name", VARCHAR), field("host", VARCHAR))
          .select(dsl.select(field("user", VARCHAR), field("host", VARCHAR)).from(table("user")))
      }

    for {
      dsl <- ZIO.attempt(makeDsl())
      sql1 <- sql1Task(dsl)
      sql2 <- sql2Task(dsl)
      _ <- using(ds, IsolationLevel.ReadCommitted)
        .execute { tx =>
          for {
            _ <- using(tx.connection).update(sql1.getSQL, Seq.empty)
            _ <- using(tx.connection).update(sql2.getSQL, Seq.empty)
          } yield ()
        }
    } yield ()
  end copyUsers

  private def getUsersFromSecondUserTable(ds: DataSource): Task[Seq[User]] =
    for {
      dsl <- ZIO.attempt(makeDsl())
      result <- using(ds, IsolationLevel.ReadCommitted)
        .query(dsl.select(field("host", VARCHAR), field("name", VARCHAR)).from(table("user_summary")).getSQL, Seq.empty) { (rs, _) =>
          User(rs.getString(1), rs.getString(2))
        }
    } yield result
  end getUsersFromSecondUserTable

  private def dropSecondUserTable(ds: DataSource): Task[Unit] =
    for {
      dsl <- ZIO.attempt(makeDsl())
      _ <- using(ds, IsolationLevel.ReadCommitted)
        .update(dsl.dropTable(table("user_summary")).getSQL, Seq.empty)
        .unit
    } yield ()
  end dropSecondUserTable

  // See https://github.com/brettwooldridge/HikariCP for connection pooling

  private def getDataSource(): Task[DataSource] =
    ZIO.attempt {
      val config = new HikariConfig("/hikari.properties") // Also tries the classpath to read from
      new HikariDataSource(config)
    }

end JdbcSupportTest
