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

import scala.util.chaining.*

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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

  private final case class Timezone(id: Int, name: String, )

  def spec = suite("ZIO-based JDBC Support test") {
    // Probably wasteful to start connection pool on each test.

    List(
      test("Querying for users succeeds") {
        for {
          result <- getDataSource()
            .flatMap(ds => getUsers(ds))
            .tap(res => printLine(s"Result: $res"))
        } yield assert(result.map(_.host).distinct)(equalTo(Seq("%", "localhost")))
      },
      test("Querying multiple times simultaneously for users succeeds") {
        for {
          multipleResults <- getDataSource()
            .flatMap(ds => getUsers(ds))
            .pipe(getDs => ZIO.foreachPar(0.until(12))(_ => getDs))
        } yield assert(multipleResults.flatten.map(_.host).distinct)(equalTo(Seq("%", "localhost")))
      }
    )
  }

  private def getUsers(ds: DataSource): Task[Seq[User]] =
    use(ds)
      .execute { conn =>
        use(conn).query("select host, user from user", Seq.empty) { (rs, _) =>
          User(rs.getString(1), rs.getString(2))
        }
      }
      .pipe(blocking(_))

  // See https://github.com/brettwooldridge/HikariCP for connection pooling

  private def getDataSource(): Task[DataSource] =
    Task.attempt {
      val config = new HikariConfig("/hikari.properties") // Also tries the classpath to read from
      new HikariDataSource(config)
    }

end JdbcSupportTest
