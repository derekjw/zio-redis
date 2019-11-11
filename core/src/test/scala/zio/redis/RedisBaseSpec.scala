package zio.redis

import zio.{ZIO, ZSchedule}
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
              MockRedis.ping returns unit
            )
        },
        testM("get mock!") {
          Redis.>.get("theKey")
            .as[String]
            .map(assert(_, equalTo(Some("theValue"))))
            .provideManaged(
              MockRedis.get(equalTo(Write("theKey"))) returns value(Some(Write("theValue")))
            )
        }
      )
    )

object TestRun extends zio.App {
  def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = {
    val action = ZStream.fromEffect(Redis.>.set("foo3", "bar3").fork).repeat(ZSchedule.recurs(100000)).runDrain *> Redis.>.get("foo3").as[String]
    val app = for {
      _ <- action
      result <- action.timed
      _ <- zio.console.putStrLn(result._1.toMillis.toString)
      _ <- zio.console.putStrLn(result._2.getOrElse("NULL"))
    } yield ()
    app.untraced.provideManaged(Redis.live(6379) @@ enrichWith[zio.console.Console](zio.console.Console.Live) @@ enrichWith[zio.clock.Clock](zio.clock.Clock.Live)).catchAll { e =>
      zio.console.putStrLn(e.toString)
    }.as(0)
  }
}