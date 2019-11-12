package zio.redis

import zio.redis.protocol.Constants
import zio.redis.serialization.{Read, SerializationFailure, Write}
import zio.{Chunk, Managed, ZIO}

trait Redis {
  val redis: Redis.Service[Any]
}

object Redis {
  trait Service[R] {
    def keys[A: Write](pattern: A): MultiValueResult[R]
    def allkeys: MultiValueResult[R] = keys(Constants.ALLKEYS)
    def randomKey: OptionalResult[R]
    def rename[A: Write, B: Write](oldKey: A, newKey: B): ZIO[R, Exception, Unit]
    def renamenx[A: Write, B: Write](oldKey: A, newKey: B): ZIO[R, Nothing, Boolean]
    def dbsize: ZIO[R, Nothing, Int]
    def exists[A: Write](key: A): ZIO[R, Nothing, Boolean]
    def del[A: Write](keys: Iterable[A]): ZIO[R, Nothing, Int]
    def ping: ZIO[R, Exception, Unit]
    def get[A: Write](key: A): OptionalResult[R]
    def set[A: Write, B: Write](key: A, value: B): ZIO[R, Exception, Unit]
  }

  def service(port: Int): Managed[Exception, Redis.Service[Any]] = RedisClient(port)
  def live(port: Int): Managed[Exception, Redis] = service(port).map(r => new Redis { val redis: Service[Any] = r })

  object > extends Service[Redis] {
    def keys[A: Write](pattern: A): MultiValueResult[Redis] = MultiValueResult(ZIO.accessM[Redis](_.redis.keys(pattern).bytes))
    def randomKey: OptionalResult[Redis] = OptionalResult(ZIO.accessM[Redis](_.redis.randomKey.bytes))
    def rename[A: Write, B: Write](oldKey: A, newKey: B): ZIO[Redis, Exception, Unit] = ZIO.accessM[Redis](_.redis.rename(oldKey, newKey))
    def renamenx[A: Write, B: Write](oldKey: A, newKey: B): ZIO[Redis, Nothing, Boolean] = ZIO.accessM[Redis](_.redis.renamenx(oldKey, newKey))
    def dbsize: ZIO[Redis, Nothing, Int] = ZIO.accessM[Redis](_.redis.dbsize)
    def exists[A: Write](key: A): ZIO[Redis, Nothing, Boolean] = ZIO.accessM[Redis](_.redis.exists(key))
    def del[A: Write](keys: Iterable[A]): ZIO[Redis, Nothing, Int] = ZIO.accessM[Redis](_.redis.del(keys))
    def ping: ZIO[Redis, Exception, Unit] = ZIO.accessM[Redis](_.redis.ping)
    def get[A: Write](key: A): OptionalResult[Redis] = OptionalResult(ZIO.accessM[Redis](_.redis.get(key).bytes))
    def set[A: Write, B: Write](key: A, value: B): ZIO[Redis, Exception, Unit] = ZIO.accessM[Redis](_.redis.set(key, value))
  }

  case class OptionalResult[R](bytes: ZIO[R, Exception, Option[Chunk[Byte]]]) {
    def unit: ZIO[R, Exception, Unit] = bytes.unit
    def as[A: Read]: ZIO[R, Exception, Option[A]] = bytes.flatMap {
      case Some(chunk) =>
        Read(chunk) match {
          case Right(value)  => ZIO.some(value)
          case Left(failure) => ZIO.fail(failure)
        }
      case None => ZIO.none
    }
  }

  case class MultiValueResult[R](bytes: ZIO[R, Nothing, Chunk[Chunk[Byte]]]) {
    def unit: ZIO[R, Nothing, Unit] = bytes.unit
    def as[A: Read]: ZIO[R, SerializationFailure, Chunk[A]] = ??? //bytes.flatMap(chunk => ZIO.fromEither(Read(chunk)))
  }

}
