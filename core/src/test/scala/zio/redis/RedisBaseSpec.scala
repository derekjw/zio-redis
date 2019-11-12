package zio.redis

import zio.{ZEnv, ZIO}
import zio.redis.serialization.Write
import zio.test._
import zio.test.mock.Expectation.{unit, value}
import zio.test.Assertion.equalTo
import zio.macros.delegate._
import zio.stream.ZStream

object RedisBaseSpec
    extends DefaultRunnableSpec(
      suite("RedisBaseSpec")(
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
              MockRedis.get(equalTo(Write("theKey"))).returns(value(Some(Write("theValue"))))
            )
        }
      )
    )

object TestRun extends zio.App {
  def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = {
    val action = ZStream.range(0, 100000).mapMPar(128)(n => Redis.>.set("foo3", "bar" + n)).runDrain *> Redis.>.get("foo3").as[String]
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
