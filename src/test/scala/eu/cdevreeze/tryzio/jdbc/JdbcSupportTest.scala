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
import eu.cdevreeze.tryzio.jdbc.JdbcSupport.Argument.*
import javax.sql.DataSource
import org.jooq.*
import org.jooq.impl.DSL
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.primaryKey
import org.jooq.impl.DSL.table
import org.jooq.impl.SQLDataType.*
import zio.Console.printLine
import zio.Task
import zio.ZIO
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

  private final case class User(host: String, user: String)

  private final case class Timezone(id: Int, name: String, useLeapSeconds: Boolean)

  def spec = suite("ZIO-based JDBC Support test") {
    // Probably wasteful to start connection pool on each test.

    List(
      test("Querying for users succeeds") {
        for {
          result <- getDataSource()
            .flatMap(ds => getUsers(ds))
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
          multipleResults <- getDataSource()
            .flatMap(ds => getUsers(ds))
            .pipe(getDs => ZIO.foreachPar(0.until(5))(_ => getDs))
        } yield assert(multipleResults.flatten.map(_.host).distinct)(equalTo(Seq("%", "localhost")))
      },
      test("Querying for timezones succeeds") {
        for {
          result <- getDataSource()
            .flatMap(ds => getSomeTimezones("Europe/%", ds))
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
          multipleResults <- getDataSource()
            .flatMap(ds => getSomeTimezones("Europe/%", ds))
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

  private def getUsers(ds: DataSource): Task[Seq[User]] =
    for {
      sqlQuery <- Task.attempt(DSL.select(field("host", VARCHAR), field("user", VARCHAR)).from(table("user")))
      result <- using(ds)
        .query(sqlQuery.getSQL, Seq.empty) { (rs, _) => User(rs.getString(1), rs.getString(2)) }
    } yield result
  end getUsers

  private def getUsersTheHardWay(ds: DataSource): Task[Seq[User]] =
    def createPreparedStatement(conn: Connection): Task[PreparedStatement] =
      Task.attempt {
        conn.prepareStatement(DSL.select(field("host", VARCHAR), field("user", VARCHAR)).from(table("user")).getSQL)
      }

    using(ds)
      .queryForSingleResult(createPreparedStatement) { rs =>
        // This unsafe code is wrapped in a Task by function queryForSingleColumn
        Iterator.from(1).takeWhile(_ => rs.next).map(_ => User(rs.getString(1), rs.getString(2))).toSeq
      }
  end getUsersTheHardWay

  private def getUsersTheVerboseWay(ds: DataSource): Task[Seq[User]] =
    using(ds)
      .execute { conn =>
        using(conn)
          .query(DSL.select(field("host", VARCHAR), field("user", VARCHAR)).from(table("user")).getSQL, Seq.empty) { (rs, _) =>
            User(rs.getString(1), rs.getString(2))
          }
      }
  end getUsersTheVerboseWay

  private def getSomeTimezones(timezoneLikeString: String, ds: DataSource): Task[Seq[Timezone]] =
    val sqlQueryTask: Task[Query] = ZIO.attempt {
      DSL
        .select(field("t.time_zone_id", INTEGER), field("tn.name", VARCHAR), field("t.use_leap_seconds", CHAR(1)))
        .from(table("time_zone t"))
        .join(table("time_zone_name tn"))
        .on(field("t.time_zone_id", INTEGER).equal(field("tn.time_zone_id", INTEGER)))
        .where(field("tn.name", VARCHAR).like(DSL.`val`("dummyNameArg")))
    }

    for {
      sqlQuery <- sqlQueryTask
      result <- using(ds)
        .query(sqlQuery.getSQL, Seq(StringArg(timezoneLikeString))) { (rs, _) =>
          Timezone(rs.getInt(1), rs.getString(2), rs.getString(3).pipe(_ == "Y"))
        }
    } yield result
  end getSomeTimezones

  private def createSecondUserTable(ds: DataSource): Task[Unit] =
    val sqlStatTask: Task[CreateTableConstraintStep] = ZIO.attempt {
      DSL
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
    val sql1Task: Task[Delete[_]] = Task.attempt { DSL.deleteFrom(table("user_summary")) }
    val sql2Task: Task[Insert[_]] = Task.attempt {
      DSL
        .insertInto(table("user_summary"))
        .columns(field("name", VARCHAR), field("host", VARCHAR))
        .select(DSL.select(field("user", VARCHAR), field("host", VARCHAR)).from(table("user")))
    }

    for {
      sql1 <- sql1Task
      sql2 <- sql2Task
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
    using(ds, IsolationLevel.ReadCommitted)
      .query(DSL.select(field("host", VARCHAR), field("name", VARCHAR)).from(table("user_summary")).getSQL, Seq.empty) { (rs, _) =>
        User(rs.getString(1), rs.getString(2))
      }
  end getUsersFromSecondUserTable

  private def dropSecondUserTable(ds: DataSource): Task[Unit] =
    using(ds, IsolationLevel.ReadCommitted)
      .update(DSL.dropTable(table("user_summary")).getSQL, Seq.empty)
      .unit
  end dropSecondUserTable

  // See https://github.com/brettwooldridge/HikariCP for connection pooling

  private def getDataSource(): Task[DataSource] =
    Task.attempt {
      val config = new HikariConfig("/hikari.properties") // Also tries the classpath to read from
      new HikariDataSource(config)
    }

end JdbcSupportTest
