package zio.redis

import zio.internal.Platform
import zio.{Chunk, Fiber, ZEnv, ZIO}
import zio.console.putStrLn
import zio.redis.serialization.Write
import zio.redis.mock.MockRedis
import zio.test._
import zio.test.mock.Expectation.{unit, value}
import zio.test.Assertion._
import zio.redis.protocol.Constants

object RedisBaseSpec extends DefaultRunnableSpec {
  import MockRedis._

  def spec =
    suite("RedisBaseSpec")(
      testM("ping") {
        Redis.>.ping
          .as(assertCompletes)
          .provideLayer(
            ping.returns(unit)
          )
      },
      testM("get") {
        Redis.>.get("theKey")
          .as[String]
          .map(assert(_)(equalTo(Some("theValue"))))
          .provideLayer(
            get(equalTo(Write("theKey"))).returns(value(Some(Write("theValue"))))
          )
      },
      testM("allkeys") {
        Redis.>.allkeys
          .as[List[String]]
          .map(assert(_)(equalTo(List("key1", "key2"))))
          .provideLayer(
            keys(equalTo(Constants.ALLKEYS)).returns(value(Chunk(Write("key1"), Write("key2"))))
          )
      }
    )
}

object TestRun extends zio.App {
  override val platform: Platform = Platform.benchmark

  def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = {
    val ops = 300000
    val key = Write("foo")
    val action = ZIO.foreach(0 until ops)(n => Redis.>.set(key, Write("bar" + n)).fork).flatMap(ZIO.foreach_(_)(_.join)) *> Redis.>.get("foo").as[String]
    val env = Redis.live(6379) ++ ZEnv.live

    val app = for {
      _ <- Fiber.fiberName.set(Some("TestRun"))
      _ <- action
      result <- action.timed
      opsPerSec = ops * 1000 / result._1.toMillis
      _ <- putStrLn(s"$opsPerSec/s")
      _ <- putStrLn(result._2.getOrElse("NULL"))
      keyResult <- Redis.>.allkeys.as[List[String]]
      _ <- putStrLn(s"All keys: $keyResult")
    } yield ()

//    val dump = Fiber.dump.flatMap(ZIO.foreach_(_)(_.prettyPrintM.flatMap(putStrLn)))

    app.provideLayer(env).orDie.as(0)
  }
}
