package zio.redis.mock

import zio.redis.{Redis, RedisClientFailure}
import zio.redis.serialization.Write
import zio.test.mock
import zio.test.mock.Method
import zio.{Chunk, Has, IO, URLayer, ZLayer}

object MockRedis {

  sealed trait Tag[I, A] extends Method[Redis, I, A] {
    override def envBuilder: URLayer[Has[mock.Proxy], Redis] = MockRedis.envBuilder
  }

  object keys extends Tag[Chunk[Byte], Chunk[Chunk[Byte]]]
  object randomKey extends Tag[Unit, Option[Chunk[Byte]]]
  object rename extends Tag[(Chunk[Byte], Chunk[Byte]), Unit]
  object renamenx extends Tag[(Chunk[Byte], Chunk[Byte]), Boolean]
  object dbsize extends Tag[Unit, Long]
  object exists extends Tag[Iterable[Chunk[Byte]], Long]
  object del extends Tag[Iterable[Chunk[Byte]], Long]
  object get extends Tag[Chunk[Byte], Option[Chunk[Byte]]]
  object set extends Tag[(Chunk[Byte], Chunk[Byte]), Unit]
  object ping extends Tag[Unit, Unit]

  private val envBuilder: URLayer[Has[mock.Proxy], Redis] = ZLayer.fromService { invoke =>
    new Redis.Service {
      override def keys[A: Write](pattern: A): Redis.MultiValueResult[Any] = Redis.MultiValueResult(invoke(MockRedis.keys, Write(pattern)))
      override def randomKey: Redis.OptionalResult[Any] = Redis.OptionalResult(invoke(MockRedis.randomKey))
      override def rename[A: Write, B: Write](oldKey: A, newKey: B): IO[RedisClientFailure, Unit] = invoke(MockRedis.rename, (Write(oldKey), Write(newKey)))
      override def renamenx[A: Write, B: Write](oldKey: A, newKey: B): IO[RedisClientFailure, Boolean] = invoke(MockRedis.renamenx, (Write(oldKey), Write(newKey)))
      override def dbsize: IO[RedisClientFailure, Long] = invoke(MockRedis.dbsize)
      override def exists[A: Write](keys: Iterable[A]): IO[RedisClientFailure, Long] = invoke(MockRedis.exists, keys.map(Write(_)))
      override def del[A: Write](keys: Iterable[A]): IO[RedisClientFailure, Long] = invoke(MockRedis.del, keys.map(Write(_)))
      override def ping: IO[RedisClientFailure, Unit] = invoke(MockRedis.ping)
      override def get[A: Write](key: A): Redis.OptionalResult[Any] = Redis.OptionalResult(invoke(MockRedis.get, Write(key)))
      override def set[A: Write, B: Write](key: A, value: B): IO[Nothing, Unit] = invoke(MockRedis.set, (Write(key), Write(value)))
    }
  }

}
