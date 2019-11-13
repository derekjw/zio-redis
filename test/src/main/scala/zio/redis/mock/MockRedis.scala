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
  object get extends Method[MockRedis, Chunk[Byte], Option[Chunk[Byte]]]
  object set extends Method[MockRedis, (Chunk[Byte], Chunk[Byte]), Unit]
  object ping extends Method[MockRedis, Unit, Unit]

  implicit val mockable: Mockable[MockRedis] = (mock: Mock) =>
    new MockRedis {
      override val redis: Service[Any] = new Service[Any] {
        override def keys[A: Write](pattern: A): Redis.MultiValueResult[Any] = Redis.MultiValueResult(mock(MockRedis.keys, Write(pattern)).flatMap(ZIO.succeed))
        override def randomKey: Redis.OptionalResult[Any] = ???
        override def rename[A: Write, B: Write](oldKey: A, newKey: B): ZIO[Any, Nothing, Unit] = ???
        override def renamenx[A: Write, B: Write](oldKey: A, newKey: B): ZIO[Any, Nothing, Boolean] = ???
        override def dbsize: ZIO[Any, Nothing, Int] = ???
        override def exists[A: Write](key: A): ZIO[Any, Nothing, Boolean] = ???
        override def del[A: Write](keys: Iterable[A]): ZIO[Any, Nothing, Int] = ???
        override def ping: ZIO[Any, Nothing, Unit] = mock(MockRedis.ping)
        override def get[A: Write](key: A): Redis.OptionalResult[Any] = Redis.OptionalResult(mock(MockRedis.get, Write(key)).flatMap(ZIO.succeed))
        override def set[A: Write, B: Write](key: A, value: B): ZIO[Any, Nothing, Unit] = mock(MockRedis.set, (Write(key), Write(value)))
      }
    }
}
