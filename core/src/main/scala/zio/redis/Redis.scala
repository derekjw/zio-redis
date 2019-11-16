package zio.redis

import zio.redis.protocol.Constants
import zio.redis.serialization.{MultiRead, Read, Write}
import zio.{Chunk, Managed, ZIO}

trait Redis {
  val redis: Redis.Service[Any]
}

object Redis {
  trait Service[R] {
    def keys[A: Write](pattern: A): MultiValueResult[R]
    def allkeys: MultiValueResult[R] = keys(Constants.ALLKEYS)
    def randomKey: OptionalResult[R]
    def rename[A: Write, B: Write](oldKey: A, newKey: B): ZIO[R, RedisClientFailure, Unit]
    def renamenx[A: Write, B: Write](oldKey: A, newKey: B): ZIO[R, RedisClientFailure, Boolean]
    def dbsize: ZIO[R, RedisClientFailure, Long]
    def exists[A: Write](keys: Iterable[A]): ZIO[R, RedisClientFailure, Long]
    def exists[A: Write](key: A): ZIO[R, RedisClientFailure, Boolean] = exists(List(key)).map(_ == 1)
    def del[A: Write](keys: Iterable[A]): ZIO[R, RedisClientFailure, Long]
    def del[A: Write](key: A): ZIO[R, RedisClientFailure, Boolean] = del(List(key)).map(_ == 1)
    def ping: ZIO[R, RedisClientFailure, Unit]
    def get[A: Write](key: A): OptionalResult[R]
    def set[A: Write, B: Write](key: A, value: B): ZIO[R, RedisClientFailure, Unit]
  }

  def service(port: Int): Managed[ConnectionFailure, Redis.Service[Any]] = RedisClient(port)
  def live(port: Int): Managed[ConnectionFailure, Redis] = service(port).map(r => new Redis { val redis: Service[Any] = r })

  object > extends Service[Redis] {
    def keys[A: Write](pattern: A): MultiValueResult[Redis] = MultiValueResult(ZIO.accessM[Redis](_.redis.keys(pattern).bytes))
    def randomKey: OptionalResult[Redis] = OptionalResult(ZIO.accessM[Redis](_.redis.randomKey.bytes))
    def rename[A: Write, B: Write](oldKey: A, newKey: B): ZIO[Redis, RedisClientFailure, Unit] = ZIO.accessM[Redis](_.redis.rename(oldKey, newKey))
    def renamenx[A: Write, B: Write](oldKey: A, newKey: B): ZIO[Redis, RedisClientFailure, Boolean] = ZIO.accessM[Redis](_.redis.renamenx(oldKey, newKey))
    def dbsize: ZIO[Redis, RedisClientFailure, Long] = ZIO.accessM[Redis](_.redis.dbsize)
    def exists[A: Write](keys: Iterable[A]): ZIO[Redis, RedisClientFailure, Long] = ZIO.accessM[Redis](_.redis.exists(keys))
    def del[A: Write](keys: Iterable[A]): ZIO[Redis, RedisClientFailure, Long] = ZIO.accessM[Redis](_.redis.del(keys))
    def ping: ZIO[Redis, RedisClientFailure, Unit] = ZIO.accessM[Redis](_.redis.ping)
    def get[A: Write](key: A): OptionalResult[Redis] = OptionalResult(ZIO.accessM[Redis](_.redis.get(key).bytes))
    def set[A: Write, B: Write](key: A, value: B): ZIO[Redis, RedisClientFailure, Unit] = ZIO.accessM[Redis](_.redis.set(key, value))
  }

  case class OptionalResult[R](bytes: ZIO[R, RedisClientFailure, Option[Chunk[Byte]]]) {
    def unit: ZIO[R, RedisClientFailure, Unit] = bytes.unit
    def as[A: Read]: ZIO[R, RedisFailure, Option[A]] = bytes.flatMap {
      case Some(chunk) =>
        Read(chunk) match {
          case Right(value)  => ZIO.some(value)
          case Left(failure) => ZIO.fail(failure)
        }
      case None => ZIO.none
    }
  }

  case class MultiValueResult[R](bytes: ZIO[R, RedisClientFailure, Chunk[Chunk[Byte]]]) {
    def unit: ZIO[R, RedisClientFailure, Unit] = bytes.unit
    def as[A: MultiRead]: ZIO[R, RedisFailure, A] = bytes.flatMap(bs => ZIO.fromEither(MultiRead(bs)))
  }

}
