package zio.redis.commands

import zio.{Chunk, ZIO, redis}
import zio.redis.Redis
import zio.redis.serialization.Write
import zio.redis.protocol.Constants._

trait Basic {
  def keys[A: Write](pattern: A): Redis.MultiValueResult = new Redis.MultiValueResult(Chunk(KEYS, Write(pattern)))
  def keys: Redis.MultiValueResult = new Redis.MultiValueResult(Chunk(KEYS, ALLKEYS))
  def randomKey: Redis.OptionalResult = new redis.Redis.OptionalResult(Chunk.single(RANDOMKEY))
  def rename[A: Write, B: Write](oldKey: A, newKey: B): ZIO[Redis, Nothing, Unit] = Redis.executeUnit(Chunk(RENAME, Write(oldKey), Write(newKey)))
  def renamenx[A: Write, B: Write](oldKey: A, newKey: B): ZIO[Redis, Nothing, Boolean] = Redis.executeBoolean(Chunk(RENAMENX, Write(oldKey), Write(newKey)))
  def dbsize: ZIO[Redis, Nothing, Int] = Redis.executeInt(Chunk.single(DBSIZE))
  def exists[A: Write](key: A): ZIO[Redis, Nothing, Boolean] = Redis.executeBoolean(Chunk(EXISTS, Write(key)))
  def del[A: Write](keys: Iterable[A]): ZIO[Redis, Nothing, Int] = Redis.executeInt(Chunk.single(DEL) ++ Chunk.fromArray(keys.view.map(Write(_)).toArray))
  def ping: ZIO[Redis, Nothing, Unit] = Redis.executeUnit(Chunk.single(PING))
  def get[A: Write](key: A): Redis.OptionalResult = new Redis.OptionalResult(Chunk(GET, Write(key)))
  def set[A: Write, B: Write](key: A, value: B): ZIO[Redis, Nothing, Unit] = Redis.executeOptional(Chunk(SET, Write(key), Write(value))).unit
}
