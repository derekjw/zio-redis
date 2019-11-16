package zio.redis.mock

import zio.redis.Redis
import zio.redis.serialization.Write
import zio.test.mock.{Method, Mock, Mockable}
import zio.{Chunk, ZIO}

trait MockRedis extends Redis {
  val redis: MockRedis.Service[Any]
}

object MockRedis {
  trait Service[R] extends Redis.Service[R]

  object keys extends Method[MockRedis, Chunk[Byte], Chunk[Chunk[Byte]]]
  object randomKey extends Method[MockRedis, Unit, Option[Chunk[Byte]]]
  object rename extends Method[MockRedis, (Chunk[Byte], Chunk[Byte]), Unit]
  object renamenx extends Method[MockRedis, (Chunk[Byte], Chunk[Byte]), Boolean]
  object dbsize extends Method[MockRedis, Unit, Long]
  object exists extends Method[MockRedis, Iterable[Chunk[Byte]], Long]
  object del extends Method[MockRedis, Iterable[Chunk[Byte]], Long]
  object get extends Method[MockRedis, Chunk[Byte], Option[Chunk[Byte]]]
  object set extends Method[MockRedis, (Chunk[Byte], Chunk[Byte]), Unit]
  object ping extends Method[MockRedis, Unit, Unit]

  implicit val mockable: Mockable[MockRedis] = (mock: Mock) =>
    new MockRedis {
      override val redis: Service[Any] = new Service[Any] {
        override def keys[A: Write](pattern: A): Redis.MultiValueResult[Any] = Redis.MultiValueResult(mock(MockRedis.keys, Write(pattern)))
        override def randomKey: Redis.OptionalResult[Any] = Redis.OptionalResult(mock(MockRedis.randomKey))
        override def rename[A: Write, B: Write](oldKey: A, newKey: B): ZIO[Any, Exception, Unit] = mock(MockRedis.rename, (Write(oldKey), Write(newKey)))
        override def renamenx[A: Write, B: Write](oldKey: A, newKey: B): ZIO[Any, Exception, Boolean] = mock(MockRedis.renamenx, (Write(oldKey), Write(newKey)))
        override def dbsize: ZIO[Any, Exception, Long] = mock(MockRedis.dbsize)
        override def exists[A: Write](keys: Iterable[A]): ZIO[Any, Exception, Long] = mock(MockRedis.exists, keys.map(Write(_)))
        override def del[A: Write](keys: Iterable[A]): ZIO[Any, Exception, Long] = mock(MockRedis.del, keys.map(Write(_)))
        override def ping: ZIO[Any, Exception, Unit] = mock(MockRedis.ping)
        override def get[A: Write](key: A): Redis.OptionalResult[Any] = Redis.OptionalResult(mock(MockRedis.get, Write(key)))
        override def set[A: Write, B: Write](key: A, value: B): ZIO[Any, Nothing, Unit] = mock(MockRedis.set, (Write(key), Write(value)))
      }
    }
}
