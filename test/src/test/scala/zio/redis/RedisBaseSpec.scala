package zio.redis

import zio.internal.Platform
import zio.{Chunk, Fiber, ZEnv, ZIO}
import zio.logging.{LogAnnotation, Logging}
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
        val app = Redis.ping
        val env = ping returns unit
        val result = app.provideLayer(env)
        assertM(result)(isUnit)
      },
      testM("get") {
        val app = Redis.get("theKey").as[String]
        val env = get(equalTo(Write("theKey"))) returns value(Some(Write("theValue")))
        val result = app.provideLayer(env)
        assertM(result)(equalTo(Some("theValue")))
      },
      testM("allkeys") {
        val app = Redis.allkeys.as[List[String]]
        val env = keys(equalTo(Constants.ALLKEYS)) returns value(Chunk(Write("key1"), Write("key2")))
        val result = app.provideLayer(env)
        assertM(result)(equalTo(List("key1", "key2")))
      }
    )
}

object TestRun extends zio.App {
  override val platform: Platform = Platform.benchmark

  def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = {
    val ops = 500000
    val key = Write("foo")
    val action = ZIO.foreach(1 to ops)(n => Redis.set(key, Write("bar" + n)).fork).flatMap(ZIO.foreach_(_)(_.join)) *> Redis.get("foo").as[String]
    val logging = Logging.console((_, l) => l)
    val env = (logging >>> Redis.live()) ++ ZEnv.live ++ logging

    val app = Logging.locally(LogAnnotation.Name("TestRun" :: Nil)) {
      for {
        _ <- Fiber.fiberName.set(Some("TestRun"))
        _ <- action
        result <- action.timed
        opsPerSec = ops * 1000 / result._1.toMillis
        _ <- Logging.info(s"$opsPerSec/s")
        _ <- Logging.info(result._2.getOrElse("NULL"))
        keyResult <- Redis.allkeys.as[List[String]]
        _ <- Logging.info(s"All keys: $keyResult")
      } yield ()
    }

//    val dump = Fiber.dump.flatMap(ZIO.foreach_(_)(_.prettyPrintM.flatMap(putStrLn)))

    app.provideLayer(env).orDie.as(0)
  }
}
