package zio.redis

import zio.redis.protocol.Constants._
import zio.redis.serialization.Write
import zio.{Chunk, ZIO}

trait RedisClient extends Redis {
  val redisClient: RedisClient.Service[Any]
}

object RedisClient {
  trait Service[R] extends Redis.Service[R] {
    def executeUnit(request: Chunk[Chunk[Byte]]): ZIO[R, Nothing, Unit]
    def executeBoolean(request: Chunk[Chunk[Byte]]): ZIO[R, Nothing, Boolean]
    def executeInt(request: Chunk[Chunk[Byte]]): ZIO[R, Nothing, Int]
    def executeOptional(request: Chunk[Chunk[Byte]]): ZIO[R, Nothing, Option[Chunk[Byte]]]
    def executeMulti(request: Chunk[Chunk[Byte]]): ZIO[R, Nothing, Chunk[Chunk[Byte]]]

    def keys[A: Write](pattern: A): Redis.MultiValueResult[R] = Redis.MultiValueResult(executeMulti(Chunk(KEYS, Write(pattern))))
    def randomKey: Redis.OptionalResult[R] = Redis.OptionalResult(executeOptional(Chunk.single(RANDOMKEY)))
    def rename[A: Write, B: Write](oldKey: A, newKey: B): ZIO[R, Nothing, Unit] = executeUnit(Chunk(RENAME, Write(oldKey), Write(newKey)))
    def renamenx[A: Write, B: Write](oldKey: A, newKey: B): ZIO[R, Nothing, Boolean] = executeBoolean(Chunk(RENAMENX, Write(oldKey), Write(newKey)))
    def dbsize: ZIO[R, Nothing, Int] = executeInt(Chunk.single(DBSIZE))
    def exists[A: Write](key: A): ZIO[R, Nothing, Boolean] = executeBoolean(Chunk(EXISTS, Write(key)))
    def del[A: Write](keys: Iterable[A]): ZIO[R, Nothing, Int] = executeInt(Chunk.single(DEL) ++ Chunk.fromArray(keys.view.map(Write(_)).toArray))
    def ping: ZIO[R, Nothing, Unit] = executeUnit(Chunk.single(PING))
    def get[A: Write](key: A): Redis.OptionalResult[R] = Redis.OptionalResult(executeOptional(Chunk(GET, Write(key))))
    def set[A: Write, B: Write](key: A, value: B): ZIO[R, Nothing, Unit] = executeUnit(Chunk(SET, Write(key), Write(value)))
  }
}
