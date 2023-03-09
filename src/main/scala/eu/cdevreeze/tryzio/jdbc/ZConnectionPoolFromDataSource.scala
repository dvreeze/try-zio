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

import javax.sql.DataSource
import zio.*

/**
 * Connection pool created from a DataSource.
 *
 * @author
 *   Chris de Vreeze
 */
final class ZConnectionPoolFromDataSource(val dataSource: DataSource) extends ZConnectionPool:

  type Underlying = DataSource

  def underlying: Underlying = dataSource

  def invalidate(conn: ZConnection): UIO[Any] = ZIO.attempt(conn.connection.close()).ignore

  override def access[A](f: Underlying => A): Task[A] = ZIO.attemptBlocking {
    f(underlying)
  }

  def transactional[A](f: Connection => A): Task[A] =
    ZIO.scoped {
      ZIO.blocking { // Correct?
        ZIO.acquireReleaseExitWith {
          ZIO.attempt(ZConnection(dataSource.getConnection())).tap { conn =>
            ZIO.attempt(conn.connection.setAutoCommit(false))
          }
        } { (conn: ZConnection, exit: Exit[Any, Any]) =>
          val endTx: Task[Unit] = exit match {
            case Exit.Success(_) => ZIO.attempt(conn.connection.commit())
            case Exit.Failure(_) => ZIO.attempt(conn.connection.rollback())
          }
          endTx.ignore.tap { _ =>
            ZIO.attempt(conn.connection.close()).ignore
          }
        } {
          _.access(f)
        }
      }
    }

object ZConnectionPoolFromDataSource:

  val layer: ZLayer[DataSource, Throwable, ZConnectionPool] =
    ZLayer.fromFunction(ds => ZConnectionPoolFromDataSource(ds))
