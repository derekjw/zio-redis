package zio.redis

import zio.{Chunk, ZIO}
import zio.test._

object RedisBaseSpec extends DefaultRunnableSpec(
  suite("RedisBaseSpec")(
    testM("ping!") {
      Redis.ping.map(_ => assertCompletes).provide(FakeRedis) // TODO: use mocks
    }
  )
)

object FakeRedis extends Redis {
  override val redis: Redis.Service[Any] = new RedisClient.Service[Any] {
    override def executeUnit(request: Chunk[Chunk[Byte]]): ZIO[Any, Nothing, Unit] = ZIO.unit
    override def executeBoolean(request: Chunk[Chunk[Byte]]): ZIO[Any, Nothing, Boolean] = ???
    override def executeInt(request: Chunk[Chunk[Byte]]): ZIO[Any, Nothing, Int] = ???
    override def executeOptional(request: Chunk[Chunk[Byte]]): ZIO[Any, Nothing, Option[Chunk[Byte]]] = ???
    override def executeMulti(request: Chunk[Chunk[Byte]]): ZIO[Any, Nothing, Chunk[Chunk[Byte]]] = ???
  }
}
