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

import zio.*

/**
 * Connection pool, inspired by zio-jdbc.
 *
 * @author
 *   Chris de Vreeze
 */
abstract class ZConnectionPool:

  type Underlying

  def underlying: Underlying

  def invalidate(conn: ZConnection): UIO[Any]

  def access[A](f: Underlying => A): Task[A] = ZIO.attemptBlocking {
    f(underlying)
  }

  def tx[A](isolationLevel: Int): Transaction[A]

  final def txReadCommitted[A]: Transaction[A] = tx(Connection.TRANSACTION_READ_COMMITTED)

  final def txRepeatableRead[A]: Transaction[A] = tx(Connection.TRANSACTION_REPEATABLE_READ)

  final def txSerializable[A]: Transaction[A] = tx(Connection.TRANSACTION_SERIALIZABLE)
