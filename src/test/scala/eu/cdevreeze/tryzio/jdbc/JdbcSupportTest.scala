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

import java.io.File
import java.sql.ResultSet

import scala.util.Using
import scala.util.chaining.*

import zio.*
import zio.Console.printLine
import zio.config.*
import zio.config.typesafe.*
import zio.jdbc.*
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

  private final case class User(host: String, user: String)

  private final case class Timezone(id: Int, name: String, useLeapSeconds: Boolean)

  private given JdbcDecoder[User] = { (rs: ResultSet) =>
    User(rs.getString(1), rs.getString(2))
  }

  private given JdbcDecoder[Timezone] = { (rs: ResultSet) =>
    Timezone(rs.getInt(1), rs.getString(2), rs.getString(3) == "Y")
  }

  def spec = suite("ZIO-based JDBC Support test") {
    // Probably wasteful to start connection pool on each test.

    List(
      test("Querying for users succeeds") {
        for {
          result <- getUsers()
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
      }
    )
  }

  private def getUsers(): Task[Seq[User]] =
    for {
      sqlQuery <- ZIO.attempt {
        sql"select host, user from user"
      }
      result <- transaction
        .apply {
          selectAll(sqlQuery.as[User])
        }
        .provideLayer(ConnectionPools.testLayer)
    } yield result
  end getUsers

  private def getSomeTimezones(timezoneLikeString: String): Task[Seq[Timezone]] =
    val sqlQueryTask: Task[SqlFragment] = ZIO.attempt {
      sql"""
           select time_zone.time_zone_id, time_zone_name.name, time_zone.use_leap_seconds
             from time_zone
             join time_zone_name on time_zone.time_zone_id = time_zone_name.time_zone_id
            where time_zone_name.name like $timezoneLikeString
         """
    }

    for {
      sqlQuery <- sqlQueryTask
      result <- transaction
        .apply {
          selectAll(sqlQuery.as[Timezone])
        }
        .provideLayer(ConnectionPools.testLayer)
    } yield result
  end getSomeTimezones

  object ConnectionPools:

    final case class DbConfig(
        host: String,
        port: Int,
        database: String,
        user: String,
        password: String,
        otherProperties: Map[String, String]
    )

    object DbConfig:
      val configuration: Config[DbConfig] =
        Config
          .string("host")
          .zip(Config.int("port"))
          .zip(Config.string("database"))
          .zip(Config.string("user"))
          .zip(Config.string("password"))
          .zip(Config.table("otherProperties", Config.string))
          .to[DbConfig]

    val zioPoolConfigLayer: ULayer[ZConnectionPoolConfig] =
      ZLayer.succeed(ZConnectionPoolConfig.default)

    private val cpSettings: Task[DbConfig] =
      for {
        configProvider <- ZIO.attempt {
          ConfigProvider.fromHoconFile(new File(classOf[ZIO[?, ?, ?]].getResource("/db-test.hocon").toURI))
        }
        settings <- configProvider.load(DbConfig.configuration)
      } yield settings

    val connectionPoolLayer: ZLayer[ZConnectionPoolConfig, Throwable, ZConnectionPool] =
      ZLayer.fromZIO(cpSettings).flatMap { cpConfig =>
        ZConnectionPool.mysql(
          host = cpConfig.get.host,
          port = cpConfig.get.port,
          database = cpConfig.get.database,
          props = Map("user" -> cpConfig.get.user, "password" -> cpConfig.get.password) ++ cpConfig.get.otherProperties
        )
      }

    val testLayer: ZLayer[Any, Throwable, ZConnectionPool] = zioPoolConfigLayer >>> connectionPoolLayer

  end ConnectionPools

end JdbcSupportTest
