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

import javax.sql.DataSource
import zio.*

/**
 * Naive ZIO-based JDBC support, somewhat reminiscent of what Spring JDBC support achieves, but very much simplified and more "functional".
 *
 * Note that JDBC code typically must run in a blocking way, using one single thread for each use of a Connection.
 *
 * @author
 *   Chris de Vreeze
 */
object JdbcSupport:

  type ScopedTask[A] = RIO[Scope, A]

  final class Transaction(val connection: Connection, val rollbackOnly: Boolean):
    def isolationLevel: Int = connection.getTransactionIsolation
    def rollback(): Unit = connection.rollback()
    def commit(): Unit = connection.commit()
    def onlyRollback(): Transaction = Transaction(connection, rollbackOnly = true)

  object Transaction:
    def unsafe(conn: Connection): Transaction = new Transaction(conn, rollbackOnly = false)
    def start(conn: Connection, isolationLevel: Int): Transaction =
      new Transaction(
        conn.tap(_.setAutoCommit(false)).tap(_.setTransactionIsolation(isolationLevel)),
        rollbackOnly = false
      )

  end Transaction

  final case class TransactionConfig(isolationLevel: Int):
    def withIsolationLevel(newIsolationLevel: Int): TransactionConfig =
      this.copy(isolationLevel = newIsolationLevel)

  // TODO More cases

  enum PreparedStatementConfig(val sqlString: String):
    case FromQuery(override val sqlString: String) extends PreparedStatementConfig(sqlString)

    def prepareStatementUsing(conn: Connection): PreparedStatement =
      this match
        case FromQuery(sql) => conn.prepareStatement(sql)
  end PreparedStatementConfig

  extension (conn: Connection)
    def prepareStatement(psConfig: PreparedStatementConfig): PreparedStatement = psConfig.prepareStatementUsing(conn)

  // TODO Make exceptions for which to rollback configurable

  final class Transactional(val ds: DataSource, val config: TransactionConfig):
    def withIsolationLevel(newIsolationLevel: Int): Transactional =
      Transactional(ds, config.withIsolationLevel(newIsolationLevel)) // Class instance changed. Does this work?

    def call[A](f: Transaction => Task[A]): Task[A] =
      startTransactionAndCall(startTransaction())(f)

    def startTransactionAndCall[A](acquireTx: => Task[Transaction])(f: Transaction => Task[A]): Task[A] =
      val scopedTask: ScopedTask[Transaction] =
        ZIO.acquireRelease(acquireTx)(tx => finishTransaction(tx))
      ZIO.scoped(scopedTask.flatMap(tx => f(tx).tapError(_ => IO.succeed(tx.onlyRollback()))))

    def query[A](psConfig: PreparedStatementConfig)(f: PreparedStatement => Task[A]): Task[A] =
      call(tx => use(tx.connection).query(psConfig)(f))

    // TODO Improve the functions below, and also make isolation level an enum

    private def startTransaction(): Task[Transaction] =
      Task.attempt {
        val conn = ds.getConnection()
        Transaction.start(conn, config.isolationLevel)
      }

    private def finishTransaction(tx: Transaction): UIO[Unit] =
      val commitOrRollback: Task[Unit] = Task.attempt { if tx.rollbackOnly then tx.rollback() else tx.commit() }
      commitOrRollback.catchAll(_ => Task.succeed(())).ensuring(Task.succeed(tx.connection.close()))
  end Transactional

  final class UsingDataSource(val ds: DataSource):
    def call[A](f: Connection => Task[A]): Task[A] =
      acquireConnectionAndCall(Task.attempt(ds.getConnection))(f)

    def acquireConnectionAndCall[A](acquireConn: => Task[Connection])(f: Connection => Task[A]): Task[A] =
      val scopedTask: ScopedTask[Connection] =
        ZIO.acquireRelease(acquireConn)(conn => Task.succeed(conn.close()))
      ZIO.scoped(scopedTask.flatMap(f))

    def query[A](psConfig: PreparedStatementConfig)(f: PreparedStatement => Task[A]): Task[A] =
      call(conn => use(conn).query(psConfig)(f))
  end UsingDataSource

  final class UsingConnection(val conn: Connection):
    def query[A](psConfig: PreparedStatementConfig)(f: PreparedStatement => Task[A]): Task[A] =
      acquirePreparedStatementAndCall(Task.attempt(conn.prepareStatement(psConfig)))(f)

    def acquirePreparedStatementAndCall[A](acquirePs: => Task[PreparedStatement])(f: PreparedStatement => Task[A]): Task[A] =
      val scopedTask: ScopedTask[PreparedStatement] =
        ZIO.acquireRelease(acquirePs)(ps => Task.succeed(ps.close()))
      ZIO.scoped(scopedTask.flatMap(f))
  end UsingConnection

  def transactional(ds: DataSource, config: TransactionConfig): Transactional = Transactional(ds, config)

  def use(ds: DataSource): UsingDataSource = UsingDataSource(ds)

  def use(conn: Connection): UsingConnection = UsingConnection(conn)

end JdbcSupport
