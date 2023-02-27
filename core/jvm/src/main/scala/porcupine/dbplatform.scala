/*
 * Copyright 2023 Arman Bilge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package porcupine

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.std.Mutex
import cats.syntax.all.*
import scodec.bits.ByteVector

import java.sql.DriverManager
import java.sql.Types

private abstract class DatabasePlatform:
  def open[F[_]](filename: String)(implicit F: Async[F]): Resource[F, Database[F]] =
    for
      mutex <- Resource.eval(Mutex[F])
      connection <- Resource
        .fromAutoCloseable(F.blocking(DriverManager.getConnection("jdbc:sqlite:" + filename)))
    yield new:
      def prepare[A, B](query: Query[A, B]): Resource[F, Statement[F, A, B]] =
        Resource
          .fromAutoCloseable {
            mutex.lock.surround {
              F.blocking(connection.prepareStatement(query.sql))
            }
          }
          .map { statement =>
            new:
              def cursor(args: A): Resource[F, Cursor[F, B]] = mutex.lock >>
                Resource
                  .fromAutoCloseable {
                    F.blocking {
                      query.encoder.encode(args).zipWithIndex.foreach {
                        case (LiteValue.Null, i) => statement.setNull(i, Types.NULL)
                        case (LiteValue.Integer(value), i) => statement.setLong(i, value)
                        case (LiteValue.Real(value), i) => statement.setDouble(i, value)
                        case (LiteValue.Text(value), i) => statement.setString(i, value)
                        case (LiteValue.Blob(value), i) =>
                          statement.setBlob(i, value.toInputStream, value.length)
                      }
                      statement.executeQuery()
                    }
                  }
                  .map { results =>
                    new:
                      def fetch(maxRows: Int): F[(List[B], Boolean)] = F
                        .blocking {
                          statement.setFetchSize(maxRows)

                          val metaData = results.getMetaData()
                          val columnCount = metaData.getColumnCount()

                          val rows = List.newBuilder[List[LiteValue]]
                          var i = 0
                          while i < maxRows && results.next() do
                            rows += (1 to columnCount).view.map { j =>
                              results.getObject(j) match
                                case null => LiteValue.Null
                                case i: Integer => LiteValue.Integer(i.longValue())
                                case l: java.lang.Long => LiteValue.Integer(l.longValue())
                                case d: java.lang.Double => LiteValue.Real(d.doubleValue())
                                case s: String => LiteValue.Text(s)
                                case b: Array[Byte] => LiteValue.Blob(ByteVector.view(b))
                            }.toList
                            i += 1

                          (rows.result(), i == maxRows)
                        }
                        .flatMap { (rows, more) =>
                          rows
                            .traverse(query.decoder.decode.runA(_))
                            .tupleRight(more)
                            .liftTo[F]
                        }

                  }

          }
