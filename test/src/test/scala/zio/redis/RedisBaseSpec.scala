package zio.redis

import zio.{ZEnv, ZIO}
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
  def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = {
    val key = Write("foo3")
    val action = (0 until 100000).map(n => Redis.>.set(key, Write("bar" + n))).reduceLeft(_.zipWithPar(_)((_, _) => ())) *> Redis.>.get("foo3").as[String]
    val app = for {
      _ <- action
      result <- action.timed
      _ <- zio.console.putStrLn(result._1.toMillis.toString)
      _ <- zio.console.putStrLn(result._2.getOrElse("NULL"))
    } yield ()
    app.untraced
      .provideManaged(Redis.live(6379) @@ enrichWith[ZEnv](Environment))
      .catchAll { e =>
        zio.console.putStrLn(e.toString)
      }
      .as(0)
  }
}
