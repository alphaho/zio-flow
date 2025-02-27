/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
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

package zio.flow.internal

import zio._
import zio.flow.internal.IndexedStore.Index
import zio.stream._

import java.io.IOException

trait DurableLog {
  def append(topic: String, value: Chunk[Byte]): IO[IOException, Index]
  def subscribe(topic: String, position: Index): ZStream[Any, IOException, Chunk[Byte]]
  def getAllAvailable(topic: String, position: Index): ZStream[Any, IOException, Chunk[Byte]]
}

object DurableLog {
  def append(topic: String, value: Chunk[Byte]): ZIO[DurableLog, IOException, Index] =
    ZIO.serviceWithZIO(_.append(topic, value))

  def subscribe(topic: String, position: Index): ZStream[DurableLog, IOException, Chunk[Byte]] =
    ZStream.serviceWithStream(_.subscribe(topic, position))

  def getAllAvailable(topic: String, position: Index): ZStream[DurableLog, IOException, Chunk[Byte]] =
    ZStream.serviceWithStream(_.getAllAvailable(topic, position))

  val live: ZLayer[IndexedStore, Nothing, DurableLog] =
    ZLayer.scoped {
      for {
        indexedStore <- ZIO.service[IndexedStore]
        topics       <- Ref.make[Map[String, Topic]](Map.empty)
        durableLog    = DurableLogLive(topics, indexedStore)
      } yield durableLog
    }

  private final case class DurableLogLive(
    topics: Ref[Map[String, Topic]],
    indexedStore: IndexedStore
  ) extends DurableLog {
    def append(topic: String, value: Chunk[Byte]): IO[IOException, Index] =
      getTopic(topic).flatMap { case Topic(hub, semaphore) =>
        semaphore.withPermit {
          indexedStore.put(topic, value).flatMap { position =>
            hub.publish(value -> position) *>
              ZIO.succeedNow(position)
          }
        }
      }
    def subscribe(topic: String, position: Index): ZStream[Any, IOException, Chunk[Byte]] =
      ZStream.unwrapScoped {
        getTopic(topic).flatMap { case Topic(hub, _) =>
          hub.subscribe.flatMap { subscription =>
            subscription.size.flatMap { size =>
              if (size > 0)
                subscription.take.map { case (value, index) =>
                  indexedStore.scan(topic, position, index) ++
                    ZStream(value) ++
                    ZStream.fromQueue(subscription).map(_._1)
                }
              else
                indexedStore.position(topic).map { currentPosition =>
                  indexedStore.scan(topic, position, currentPosition) ++
                    collectFrom(ZStream.fromQueue(subscription), currentPosition)
                }
            }
          }
        }
      }

    def getAllAvailable(topic: String, position: IndexedStore.Index): ZStream[Any, IOException, Chunk[Byte]] =
      ZStream.unwrap {
        indexedStore.position(topic).map { current =>
          indexedStore.scan(topic, position, current)
        }
      }

    private def collectFrom(
      stream: ZStream[Any, IOException, (Chunk[Byte], Index)],
      position: Index
    ): ZStream[Any, IOException, Chunk[Byte]] =
      stream.collect { case (value, index) if index >= position => value }

    private def getTopic(topic: String): UIO[Topic] =
      topics.modify { topics =>
        topics.get(topic) match {
          case Some(Topic(hub, semaphore)) =>
            Topic(hub, semaphore) -> topics
          case None =>
            val hub       = unsafeMakeHub[(Chunk[Byte], Index)]()
            val semaphore = unsafeMakeSemaphore(1)
            Topic(hub, semaphore) -> topics.updated(topic, Topic(hub, semaphore))
        }
      }
  }

  private final case class Topic(hub: Hub[(Chunk[Byte], Index)], semaphore: Semaphore)

  private object Topic {
    def unsafeMake(): Topic =
      Topic(unsafeMakeHub[(Chunk[Byte], Index)](), unsafeMakeSemaphore(1))
  }

  private def unsafeMakeHub[A](): Hub[A] =
    Runtime.default.unsafeRun(Hub.unbounded[A])

  private def unsafeMakeSemaphore(permits: Long): Semaphore =
    Runtime.default.unsafeRun(Semaphore.make(permits))
}
