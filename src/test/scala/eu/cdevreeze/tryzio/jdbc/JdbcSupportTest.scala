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

import scala.util.chaining.*

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import eu.cdevreeze.tryzio.jdbc.JdbcSupport.Argument.*
import javax.sql.DataSource
import zio.Console.printLine
import zio.Task
import zio.ZIO
import zio.ZIO.blocking
import zio.test.Assertion.*
import zio.test.DefaultRunnableSpec
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
object JdbcSupportTest extends DefaultRunnableSpec:

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
      test("Inserting users into the second user table succeeds") {
        // The inserted Users will currently remain in the new table until a new MySQL container is created.
        for {
          ds <- getDataSource()
          _ <- createSecondUserTable(ds)
          _ <- copyUsers(ds)
          users <- getUsersFromSecondUserTable(ds)
        } yield {
          assert(users)(contains(User("localhost", "root"))) &&
          assert(users)(contains(User("%", "root")))
        }
      }
    )
  }

  private def getUsers(ds: DataSource): Task[Seq[User]] =
    use(ds)
      .query("select host, user from user", Seq.empty) { (rs, _) => User(rs.getString(1), rs.getString(2)) }
      .pipe(blocking(_))
  end getUsers

  private def getSomeTimezones(timezoneLikeString: String, ds: DataSource): Task[Seq[Timezone]] =
    val sql =
      """select t.time_zone_id, tn.name, t.use_leap_seconds
        |  from time_zone t
        |  join time_zone_name tn
        |    on t.time_zone_id = tn.time_zone_id
        | where tn.name like ?""".stripMargin

    use(ds)
      .query(sql, Seq(StringArg(timezoneLikeString))) { (rs, _) =>
        Timezone(rs.getInt(1), rs.getString(2), rs.getString(3).pipe(_ == "Y"))
      }
      .pipe(blocking(_))
  end getSomeTimezones

  private def createSecondUserTable(ds: DataSource): Task[Unit] =
    val sql =
      """create table if not exists user_summary (
        |  name varchar(255) not null,
        |  host varchar(255) not null,
        |  primary key (name, host)
        |)""".stripMargin

    transactional(ds, TransactionConfig(IsolationLevel.ReadCommitted))
      .executeStatement(sql, Seq.empty)
      .unit
      .pipe(blocking(_))
  end createSecondUserTable

  private def copyUsers(ds: DataSource): Task[Unit] =
    val sql1 = "delete from user_summary"
    val sql2 = "insert into user_summary (name, host) select user, host from user"

    transactional(ds, TransactionConfig(IsolationLevel.ReadCommitted))
      .execute { tx =>
        for {
          _ <- use(tx.connection).executeStatement(sql1, Seq.empty)
          _ <- use(tx.connection).executeStatement(sql2, Seq.empty)
        } yield ()
      }
      .pipe(blocking(_))
  end copyUsers

  private def getUsersFromSecondUserTable(ds: DataSource): Task[Seq[User]] =
    transactional(ds, TransactionConfig(IsolationLevel.ReadCommitted))
      .query("select host, name from user_summary", Seq.empty) { (rs, _) =>
        User(rs.getString(1), rs.getString(2))
      }
      .pipe(blocking(_))
  end getUsersFromSecondUserTable

  // See https://github.com/brettwooldridge/HikariCP for connection pooling

  private def getDataSource(): Task[DataSource] =
    Task.attempt {
      val config = new HikariConfig("/hikari.properties") // Also tries the classpath to read from
      new HikariDataSource(config)
    }

end JdbcSupportTest
