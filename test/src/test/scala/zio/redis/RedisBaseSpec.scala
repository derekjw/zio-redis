package zio.redis

import zio.internal.{Platform, PlatformLive}
import zio.{Chunk, ZEnv, ZIO}
import zio.console.putStrLn
import zio.redis.serialization.Write
import zio.redis.mock.MockRedis
import zio.test._
import zio.test.mock.Expectation.{unit, value}
import zio.test.Assertion.equalTo
import zio.macros.delegate._
import zio.redis.protocol.Constants

object RedisBaseSpec
    extends DefaultRunnableSpec(
      suite("RedisBaseSpec")(
        testM("ping") {
          Redis.>.ping
            .as(assertCompletes)
            .provideManaged(
              MockRedis.ping.returns(unit)
            )
        },
        testM("get") {
          Redis.>.get("theKey")
            .as[String]
            .map(assert(_, equalTo(Some("theValue"))))
            .provideManaged(
              MockRedis.get(equalTo(Write("theKey"))).returns(value(Some(Write("theValue"))))
            )
        },
        testM("allkeys") {
          Redis.>.allkeys
            .as[List[String]]
            .map(assert(_, equalTo(List("key1", "key2"))))
            .provideManaged(
              MockRedis.keys(equalTo(Constants.ALLKEYS)).returns(value(Chunk(Write("key1"), Write("key2"))))
            )
        }
      )
    )

object TestRun extends zio.App {
  override val Platform: Platform = PlatformLive.Benchmark

  def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = {
    val ops = 200000
    val key = Write("foo3")
    val action = (0 until ops).map(n => Redis.>.set(key, Write("bar" + n))).reduceLeft(_.zipWithPar(_)((_, _) => ())) *> Redis.>.get("foo3").as[String]
    val env = Redis.live(6379) @@ enrichWith[ZEnv](Environment)

    val app = for {
      _ <- action
      result <- action.timed
      opsPerSec = ops * 1000 / result._1.toMillis
      _ <- putStrLn(s"$opsPerSec/s")
      _ <- putStrLn(result._2.getOrElse("NULL"))
      keyResult <- Redis.>.allkeys.as[List[String]]
      _ <- putStrLn(s"All keys: $keyResult")
    } yield ()

    app.provideManaged(env).orDie.as(0)
  }
}
