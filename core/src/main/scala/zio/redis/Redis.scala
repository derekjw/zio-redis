package zio.redis

import zio.redis.protocol.Constants
import zio.redis.serialization.{MultiRead, Read, Write}
import zio.{Chunk, IO, Managed, ZIO, ZLayer}

object Redis {
  trait Service {
    def keys[A: Write](pattern: A): MultiValueResult[Any]
    def allkeys: MultiValueResult[Any] = keys(Constants.ALLKEYS)
    def randomKey: OptionalResult[Any]
    def rename[A: Write, B: Write](oldKey: A, newKey: B): IO[RedisClientFailure, Unit]
    def renamenx[A: Write, B: Write](oldKey: A, newKey: B): IO[RedisClientFailure, Boolean]
    def dbsize: IO[RedisClientFailure, Long]
    def exists[A: Write](keys: Iterable[A]): IO[RedisClientFailure, Long]
    def exists[A: Write](key: A): IO[RedisClientFailure, Boolean] = exists(List(key)).map(_ == 1)
    def del[A: Write](keys: Iterable[A]): IO[RedisClientFailure, Long]
    def del[A: Write](key: A): IO[RedisClientFailure, Boolean] = del(List(key)).map(_ == 1)
    def ping: IO[RedisClientFailure, Unit]
    def get[A: Write](key: A): OptionalResult[Any]
    def set[A: Write, B: Write](key: A, value: B): IO[RedisClientFailure, Unit]
  }

  def service(port: Int): Managed[ConnectionFailure, Redis.Service] = RedisClient(port)
  def live(port: Int): ZLayer.NoDeps[ConnectionFailure, Redis] = ZLayer.fromManaged(service(port))

  object > {
    def keys[A: Write](pattern: A): MultiValueResult[Redis] = MultiValueResult(ZIO.accessM[Redis](_.get.keys(pattern).bytes))
    def allkeys: MultiValueResult[Redis] = keys(Constants.ALLKEYS)
    def randomKey: OptionalResult[Redis] = OptionalResult(ZIO.accessM[Redis](_.get.randomKey.bytes))
    def rename[A: Write, B: Write](oldKey: A, newKey: B): ZIO[Redis, RedisClientFailure, Unit] = ZIO.accessM[Redis](_.get.rename(oldKey, newKey))
    def renamenx[A: Write, B: Write](oldKey: A, newKey: B): ZIO[Redis, RedisClientFailure, Boolean] = ZIO.accessM[Redis](_.get.renamenx(oldKey, newKey))
    def dbsize: ZIO[Redis, RedisClientFailure, Long] = ZIO.accessM[Redis](_.get.dbsize)
    def exists[A: Write](keys: Iterable[A]): ZIO[Redis, RedisClientFailure, Long] = ZIO.accessM[Redis](_.get.exists(keys))
    def del[A: Write](keys: Iterable[A]): ZIO[Redis, RedisClientFailure, Long] = ZIO.accessM[Redis](_.get.del(keys))
    def ping: ZIO[Redis, RedisClientFailure, Unit] = ZIO.accessM[Redis](_.get.ping)
    def get[A: Write](key: A): OptionalResult[Redis] = OptionalResult(ZIO.accessM[Redis](_.get.get(key).bytes))
    def set[A: Write, B: Write](key: A, value: B): ZIO[Redis, RedisClientFailure, Unit] = ZIO.accessM[Redis](_.get.set(key, value))
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
