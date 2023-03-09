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
 * Database (local) transaction created from a DataSource, and returning a value of type A.
 *
 * TODO Readonly transactions
 *
 * @author
 *   Chris de Vreeze
 */
final class DataSourceTransaction[A](val dataSource: DataSource, isolationLevel: Int) extends Transaction[A](isolationLevel):

  def apply(f: Connection => A): Task[A] =
    ZIO.scoped {
      ZIO.blocking { // Correct?
        ZIO.acquireReleaseExitWith {
          ZIO
            .attempt(ZConnection(dataSource.getConnection()))
            .tap { conn =>
              ZIO.attempt(conn.connection.setTransactionIsolation(isolationLevel))
            }
            .tap { conn =>
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