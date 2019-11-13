package zio.redis

import zio.internal.{Platform, PlatformLive}
import zio.{ZEnv, ZIO}
import zio.console.putStrLn
import zio.redis.serialization.Write
import zio.redis.mock.MockRedis
import zio.test._
import zio.test.mock.Expectation.{unit, value}
import zio.test.Assertion.equalTo
import zio.macros.delegate._

object RedisBaseSpec
    extends DefaultRunnableSpec(
      suite("RedisBaseSpec")(
        testM("ping mock!") {
          Redis.>.ping
            .as(assertCompletes)
            .provideManaged(
              MockRedis.ping.returns(unit)
            )
        },
        testM("get mock!") {
          Redis.>.get("theKey")
            .as[String]
            .map(assert(_, equalTo(Some("theValue"))))
            .provideManaged(
              MockRedis.get(equalTo(Write("theKey"))).returns(value(Some(Write("theValue"))))
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
    } yield ()

    app.provideManaged(env).orDie.as(0)
  }
}
