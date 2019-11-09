package zio.redis

import zio.{Chunk, ZIO}
import zio.test.mock.{Method, Mock, Mockable}

trait MockRedis extends Redis {
  val redis: MockRedis.Service[Any]
}

object MockRedis {
  trait Service[R] extends Redis.Service[R]

  object ping extends Method[MockRedis, Unit, Unit]

  implicit val mockable: Mockable[MockRedis] = (mock: Mock) =>
    new MockRedis {
      override val redis: Service[Any] = new Service[Any] {
        override def keys(pattern: Chunk[Byte]): ZIO[Any, Nothing, Chunk[Chunk[Byte]]] = ???
        override def keys: ZIO[Any, Nothing, Chunk[Chunk[Byte]]] = ???
        override def randomKey: ZIO[Any, Nothing, Option[Chunk[Byte]]] = ???
        override def rename(oldKey: Chunk[Byte], newKey: Chunk[Byte]): ZIO[Any, Nothing, Unit] = ???
        override def renamenx(oldKey: Chunk[Byte], newKey: Chunk[Byte]): ZIO[Any, Nothing, Boolean] = ???
        override def dbsize: ZIO[Any, Nothing, Int] = ???
        override def exists(key: Chunk[Byte]): ZIO[Any, Nothing, Boolean] = ???
        override def del(keys: Iterable[Chunk[Byte]]): ZIO[Any, Nothing, Int] = ???
        override def ping: ZIO[Any, Nothing, Unit] = mock(MockRedis.ping)
        override def get(key: Chunk[Byte]): ZIO[Any, Nothing, Option[Chunk[Byte]]] = ???
        override def set(key: Chunk[Byte], value: Chunk[Byte]): ZIO[Any, Nothing, Unit] = ???
      }
    }
}