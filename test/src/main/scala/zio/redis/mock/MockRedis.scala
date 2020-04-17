package zio.redis.mock

import zio.redis.{Redis, RedisClientFailure}
import zio.redis.serialization.Write
import zio.test.mock.{Method, Mock, Mockable}
import zio.{Chunk, Has, IO}

object MockRedis {
  object keys extends Method[Redis.Service, Chunk[Byte], Chunk[Chunk[Byte]]]
  object randomKey extends Method[Redis.Service, Unit, Option[Chunk[Byte]]]
  object rename extends Method[Redis.Service, (Chunk[Byte], Chunk[Byte]), Unit]
  object renamenx extends Method[Redis.Service, (Chunk[Byte], Chunk[Byte]), Boolean]
  object dbsize extends Method[Redis.Service, Unit, Long]
  object exists extends Method[Redis.Service, Iterable[Chunk[Byte]], Long]
  object del extends Method[Redis.Service, Iterable[Chunk[Byte]], Long]
  object get extends Method[Redis.Service, Chunk[Byte], Option[Chunk[Byte]]]
  object set extends Method[Redis.Service, (Chunk[Byte], Chunk[Byte]), Unit]
  object ping extends Method[Redis.Service, Unit, Unit]

  implicit val mockable: Mockable[Redis.Service] = (mock: Mock) =>
    Has(new Redis.Service {
      override def keys[A: Write](pattern: A): Redis.MultiValueResult[Any] = Redis.MultiValueResult(mock(MockRedis.keys, Write(pattern)))
      override def randomKey: Redis.OptionalResult[Any] = Redis.OptionalResult(mock(MockRedis.randomKey))
      override def rename[A: Write, B: Write](oldKey: A, newKey: B): IO[RedisClientFailure, Unit] = mock(MockRedis.rename, (Write(oldKey), Write(newKey)))
      override def renamenx[A: Write, B: Write](oldKey: A, newKey: B): IO[RedisClientFailure, Boolean] = mock(MockRedis.renamenx, (Write(oldKey), Write(newKey)))
      override def dbsize: IO[RedisClientFailure, Long] = mock(MockRedis.dbsize)
      override def exists[A: Write](keys: Iterable[A]): IO[RedisClientFailure, Long] = mock(MockRedis.exists, keys.map(Write(_)))
      override def del[A: Write](keys: Iterable[A]): IO[RedisClientFailure, Long] = mock(MockRedis.del, keys.map(Write(_)))
      override def ping: IO[RedisClientFailure, Unit] = mock(MockRedis.ping)
      override def get[A: Write](key: A): Redis.OptionalResult[Any] = Redis.OptionalResult(mock(MockRedis.get, Write(key)))
      override def set[A: Write, B: Write](key: A, value: B): IO[Nothing, Unit] = mock(MockRedis.set, (Write(key), Write(value)))
    })
}
