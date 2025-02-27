package zio.flow.dynamodb

import DynamoDbKeyValueStore.tableName
import DynamoDbSupport.{createDynamoDbTable, dynamoDbLayer}
import zio.flow.internal.KeyValueStore
import zio.{Chunk, ZIO}
import zio.test.Assertion.hasSameElements
import zio.test.TestAspect.{nondeterministic, sequential}
import zio.test.{Gen, ZIOSpecDefault, Spec, assert, assertTrue, checkN}
import zio.flow.internal.Timestamp
import zio.test.Assertion.isNone

object DynamoDbKeyValueStoreSpec extends ZIOSpecDefault {

  private val dynamoDbKeyValueStore =
    dynamoDbLayer >+> DynamoDbKeyValueStore.layer

  private val dynamoDbNameGen =
    Gen.alphaNumericStringBounded(
      min = 3,
      max = 255
    )

  private val nonEmptyByteChunkGen =
    Gen.chunkOf1(Gen.byte)

  private val byteChunkGen =
    Gen.chunkOf(Gen.byte)

  override def spec: Spec[Environment, Any] =
    suite("DynamoDbKeyValueStoreSpec")(
      test("should be able to `put` (upsert) a key-value pair and then `get` it back.") {
        checkN(10)(
          dynamoDbNameGen,
          nonEmptyByteChunkGen,
          byteChunkGen,
          byteChunkGen
        ) { (namespace, key, value1, value2) =>
          ZIO.scoped {
            createDynamoDbTable(tableName) *> {
              for {
                putSucceeded1 <- KeyValueStore.put(namespace, key, value1, Timestamp(1L))
                putSucceeded2 <- KeyValueStore.put(namespace, key, value2, Timestamp(2L))
                retrieved1    <- KeyValueStore.getLatest(namespace, key, Some(Timestamp(1L)))
                retrieved2    <- KeyValueStore.getLatest(namespace, key, Some(Timestamp(2L)))
              } yield assertTrue(
                putSucceeded1,
                retrieved1.get == value1,
                putSucceeded2,
                retrieved2.get == value2
              )
            }
          }
        }
      },
      test("should be able to delete a key-value pair") {
        checkN(10)(
          dynamoDbNameGen,
          nonEmptyByteChunkGen,
          byteChunkGen
        ) { (namespace, key, value1) =>
          ZIO.scoped {
            createDynamoDbTable(tableName) *> {
              for {
                _      <- KeyValueStore.put(namespace, key, value1, Timestamp(1L))
                _      <- KeyValueStore.delete(namespace, key)
                latest <- KeyValueStore.getLatest(namespace, key, Some(Timestamp(1)))
              } yield assert(latest)(isNone)
            }
          }
        }
      },
      test("should return empty result for a `get` call when the namespace does not exist.") {
        checkN(10)(
          nonEmptyByteChunkGen
        ) { key =>
          val nonExistentNamespace = newTimeBasedName()

          ZIO.scoped {
            createDynamoDbTable(tableName) *> {
              KeyValueStore
                .getLatest(nonExistentNamespace, key, None)
                .map { retrieved =>
                  assertTrue(
                    retrieved.isEmpty
                  )
                }
            }
          }
        }
      },
      test("should return empty result for a `get` call when the key does not exist.") {
        checkN(10)(
          dynamoDbNameGen,
          nonEmptyByteChunkGen,
          byteChunkGen
        ) { (namespace, key, value) =>
          val nonExistingKey =
            Chunk.fromIterable(newTimeBasedName().getBytes)

          ZIO.scoped {
            createDynamoDbTable(tableName) *> {
              for {
                putSucceeded <- KeyValueStore.put(namespace, key, value, Timestamp(1L))
                retrieved    <- KeyValueStore.getLatest(namespace, nonExistingKey, Some(Timestamp(1L)))
              } yield assertTrue(
                retrieved.isEmpty,
                putSucceeded
              )
            }
          }
        }
      },
      test("should return empty result for a `scanAll` call when the namespace does not exist.") {
        val nonExistentNamespace = newTimeBasedName()

        ZIO.scoped {
          createDynamoDbTable(tableName) *> {
            KeyValueStore
              .scanAll(nonExistentNamespace)
              .runCollect
              .map { retrieved =>
                assertTrue(retrieved.isEmpty)
              }
          }
        }
      },
      test("should return all key-value pairs for a `scanAll` call.") {
        val uniqueTableName = newTimeBasedName()
        val keyValuePairs =
          Chunk
            .fromIterable(1 to 1001)
            .map { n =>
              Chunk.fromArray(s"abc_$n".getBytes) -> Chunk.fromArray(s"xyz_$n".getBytes)
            }
        val expectedLength = keyValuePairs.length

        ZIO.scoped {
          createDynamoDbTable(tableName) *> {
            for {
              putSuccesses <-
                ZIO
                  .foreach(keyValuePairs) { case (key, value) =>
                    KeyValueStore.put(uniqueTableName, key, value, Timestamp(1L))
                  }
              retrieved <-
                KeyValueStore
                  .scanAll(uniqueTableName)
                  .runCollect
            } yield {
              assertTrue(
                putSuccesses.length == expectedLength,
                putSuccesses.toSet == Set(true),
                retrieved.length == expectedLength
              ) &&
              assert(retrieved)(
                hasSameElements(keyValuePairs)
              )
            }
          }
        }
      }
    ).provideCustomLayerShared(dynamoDbKeyValueStore) @@ nondeterministic @@ sequential

  private def newTimeBasedName() =
    s"${java.time.Instant.now}"
      .replaceAll(":", "_")
      .replaceAll(".", "_")
}
