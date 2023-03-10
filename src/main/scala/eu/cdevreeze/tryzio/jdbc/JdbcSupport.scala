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
import java.sql.Time
import java.sql.Timestamp

/**
 * Helpers for JDBC support.
 *
 * @author
 *   Chris de Vreeze
 */
object JdbcSupport:

  final case class TransactionConfig(isolationLevel: IsolationLevel):
    def withIsolationLevel(newIsolationLevel: IsolationLevel): TransactionConfig =
      this.copy(isolationLevel = newIsolationLevel)

  enum IsolationLevel(val intValue: Int):
    case NoTransactions extends IsolationLevel(Connection.TRANSACTION_NONE)
    case ReadUncommited extends IsolationLevel(Connection.TRANSACTION_READ_UNCOMMITTED)
    case ReadCommitted extends IsolationLevel(Connection.TRANSACTION_READ_COMMITTED)
    case RepeatableRead extends IsolationLevel(Connection.TRANSACTION_REPEATABLE_READ)
    case Serializable extends IsolationLevel(Connection.TRANSACTION_SERIALIZABLE)

  object IsolationLevel:
    def from(intValue: Int): IsolationLevel =
      intValue match
        case Connection.TRANSACTION_NONE             => IsolationLevel.NoTransactions
        case Connection.TRANSACTION_READ_UNCOMMITTED => IsolationLevel.ReadUncommited
        case Connection.TRANSACTION_READ_COMMITTED   => IsolationLevel.ReadCommitted
        case Connection.TRANSACTION_REPEATABLE_READ  => IsolationLevel.RepeatableRead
        case Connection.TRANSACTION_SERIALIZABLE     => IsolationLevel.Serializable

  // Far from complete at the moment
  // For generic SQL types, aka JDBC types, see class java.sql.Types
  enum Argument(val arg: Any):
    case StringArg(override val arg: String) extends Argument(arg)
    case BigDecimalArg(override val arg: BigDecimal) extends Argument(arg)
    case BooleanArg(override val arg: Boolean) extends Argument(arg)
    case ByteArg(override val arg: Byte) extends Argument(arg)
    case BytesArg(override val arg: Array[Byte]) extends Argument(arg)
    case IntArg(override val arg: Int) extends Argument(arg)
    case LongArg(override val arg: Long) extends Argument(arg)
    case NullArg(val sqlType: Int) extends Argument(None)
    case AnyRefArg(override val arg: AnyRef, val sqlType: Int) extends Argument(arg)
    case ShortArg(override val arg: Short) extends Argument(arg)
    case DateArg(override val arg: java.sql.Date) extends Argument(arg)
    case TimeArg(override val arg: Time) extends Argument(arg)
    case TimestampArg(override val arg: Timestamp) extends Argument(arg)

    def applyTo(ps: PreparedStatement, idx: Int): Unit =
      this match
        case StringArg(v)          => ps.setString(idx, v)
        case BigDecimalArg(v)      => ps.setBigDecimal(idx, v.bigDecimal)
        case BooleanArg(v)         => ps.setBoolean(idx, v)
        case ByteArg(v)            => ps.setByte(idx, v)
        case BytesArg(v)           => ps.setBytes(idx, v)
        case IntArg(v)             => ps.setInt(idx, v)
        case LongArg(v)            => ps.setLong(idx, v)
        case NullArg(sqlType)      => ps.setNull(idx, sqlType)
        case AnyRefArg(v, sqlType) => ps.setObject(idx, v, sqlType)
        case ShortArg(v)           => ps.setShort(idx, v)
        case DateArg(v)            => ps.setDate(idx, v)
        case TimeArg(v)            => ps.setTime(idx, v)
        case TimestampArg(v)       => ps.setTimestamp(idx, v)

  end Argument

end JdbcSupport
