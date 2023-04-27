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

package eu.cdevreeze.tryzio.wordpress.console

import java.io.File

import zio.*
import zio.config.*
import zio.config.typesafe.*
import zio.jdbc.*

/**
 * Connection pool ZLayer.
 *
 * @author
 *   Chris de Vreeze
 */
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
        ConfigProvider.fromHoconFile(new File(classOf[ZIO[?, ?, ?]].getResource("/db.hocon").toURI))
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

  val liveLayer: ZLayer[Any, Throwable, ZConnectionPool] = zioPoolConfigLayer >>> connectionPoolLayer

end ConnectionPools
