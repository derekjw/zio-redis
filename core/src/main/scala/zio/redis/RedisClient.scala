package zio.redis

import zio.redis.protocol.Constants._
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

    def keys(pattern: Chunk[Byte]): ZIO[R, Nothing, Chunk[Chunk[Byte]]] = executeMulti(Chunk(KEYS, pattern))
    def keys: ZIO[R, Nothing, Chunk[Chunk[Byte]]] = executeMulti(Chunk(KEYS, ALLKEYS))
    def randomKey: ZIO[R, Nothing, Option[Chunk[Byte]]] = executeOptional(Chunk.single(RANDOMKEY))
    def rename(oldKey: Chunk[Byte], newKey: Chunk[Byte]): ZIO[R, Nothing, Unit] = executeUnit(Chunk(RENAME, oldKey, newKey))
    def renamenx(oldKey: Chunk[Byte], newKey: Chunk[Byte]): ZIO[R, Nothing, Boolean] = executeBoolean(Chunk(RENAMENX, oldKey, newKey))
    def dbsize: ZIO[R, Nothing, Int] = executeInt(Chunk.single(DBSIZE))
    def exists(key: Chunk[Byte]): ZIO[R, Nothing, Boolean] = executeBoolean(Chunk(EXISTS, key))
    def del(keys: Iterable[Chunk[Byte]]): ZIO[R, Nothing, Int] = executeInt(Chunk.single(DEL) ++ Chunk.fromArray(keys.toArray))
    def ping: ZIO[R, Nothing, Unit] = executeUnit(Chunk.single(PING))
    def get(key: Chunk[Byte]): ZIO[R, Nothing, Option[Chunk[Byte]]] = executeOptional(Chunk(GET, key))
    def set(key: Chunk[Byte], value: Chunk[Byte]): ZIO[R, Nothing, Unit] = executeUnit(Chunk(SET, key, value))
  }
}
