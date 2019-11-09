package zio.redis

import zio.redis.serialization.Write
import zio.{Chunk, ZIO, redis}
import zio.test._
import zio.test.mock.Expectation.{unit, value}
import zio.test.Assertion.equalTo

object RedisBaseSpec
    extends DefaultRunnableSpec(
      suite("RedisBaseSpec")(
        testM("ping!") {
          Redis.>.ping
            .map(_ => assertCompletes)
            .provide(FakeRedis)
        },
        testM("ping mock!") {
          Redis.>.ping
            .map(_ => assertCompletes)
            .provideManaged(
              MockRedis.ping.returns(unit)
            )
        },
        testM("get mock!") {
          Redis.>.get("theKey")
            .as[String]
            .map(assert(_, equalTo(Some("theValue"))))
            .provideManaged(
              redis.MockRedis.get(equalTo(Write("theKey"))).returns(value(Some(Write("theValue"))))
            )
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
