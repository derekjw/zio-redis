package zio.redis

import zio.redis.serialization.{Read, SerializationFailure, Write}
import zio.{Chunk, ZIO}

trait Redis {
  val redis: Redis.Service[Any]
}

object Redis {
  trait Service[R] {
    def keys(pattern: Chunk[Byte]): ZIO[R, Nothing, Chunk[Chunk[Byte]]]
    def keys: ZIO[R, Nothing, Chunk[Chunk[Byte]]]
    def randomKey: ZIO[R, Nothing, Option[Chunk[Byte]]]
    def rename(oldKey: Chunk[Byte], newKey: Chunk[Byte]): ZIO[R, Nothing, Unit]
    def renamenx(oldKey: Chunk[Byte], newKey: Chunk[Byte]): ZIO[R, Nothing, Boolean]
    def dbsize: ZIO[R, Nothing, Int]
    def exists(key: Chunk[Byte]): ZIO[R, Nothing, Boolean]
    def del(keys: Iterable[Chunk[Byte]]): ZIO[R, Nothing, Int]
    def ping: ZIO[R, Nothing, Unit]
    def get(key: Chunk[Byte]): ZIO[R, Nothing, Option[Chunk[Byte]]]
    def set(key: Chunk[Byte], value: Chunk[Byte]): ZIO[R, Nothing, Unit]
  }

  case class OptionalResult(bytes: ZIO[Redis, Nothing, Option[Chunk[Byte]]]) {
    def unit: ZIO[Redis, Nothing, Unit] = bytes.unit
    def as[A: Read]: ZIO[Redis, SerializationFailure, Option[A]] = bytes.flatMap {
      case Some(chunk) =>
        Read(chunk) match {
          case Right(value) => ZIO.some(value)
          case Left(failure) => ZIO.fail(failure)
        }
      case None => ZIO.none
    }
  }

  case class MultiValueResult(bytes: ZIO[Redis, Nothing, Chunk[Chunk[Byte]]]) {
    def unit: ZIO[Redis, Nothing, Unit] = bytes.unit
    def as[A: Read]: ZIO[Redis, SerializationFailure, Chunk[A]] = ??? //bytes.flatMap(chunk => ZIO.fromEither(Read(chunk)))
  }

  private def optionalResult[A](f: Redis => ZIO[Redis, Nothing, Option[Chunk[Byte]]]) = OptionalResult(ZIO.accessM[Redis](f))
  private def multiValueResult[A](f: Redis => ZIO[Redis, Nothing, Chunk[Chunk[Byte]]]) = MultiValueResult(ZIO.accessM[Redis](f))
  private def unitResult(f: Redis => ZIO[Redis, Nothing, Unit]) = ZIO.accessM[Redis](f)
  private def booleanResult(f: Redis => ZIO[Redis, Nothing, Boolean]) = ZIO.accessM[Redis](f)
  private def intResult(f: Redis => ZIO[Redis, Nothing, Int]) = ZIO.accessM[Redis](f)

  def keys[A: Write](pattern: A): MultiValueResult = multiValueResult(_.redis.keys(Write(pattern)))
  def keys: MultiValueResult = multiValueResult(_.redis.keys)
  def randomKey: OptionalResult = optionalResult(_.redis.randomKey)
  def rename[A: Write, B: Write](oldKey: A, newKey: B): ZIO[Redis, Nothing, Unit] = unitResult(_.redis.rename(Write(oldKey), Write(newKey)))
  def renamenx[A: Write, B: Write](oldKey: A, newKey: B): ZIO[Redis, Nothing, Boolean] = booleanResult(_.redis.renamenx(Write(oldKey), Write(newKey)))
  def dbsize: ZIO[Redis, Nothing, Int] = intResult(_.redis.dbsize)
  def exists[A: Write](key: A): ZIO[Redis, Nothing, Boolean] = booleanResult(_.redis.exists(Write(key)))
  def del[A: Write](keys: Iterable[A]): ZIO[Redis, Nothing, Int] = intResult(_.redis.del(keys.view.map(Write(_))))
  def ping: ZIO[Redis, Nothing, Unit] = unitResult(_.redis.ping)
  def get[A: Write](key: A): OptionalResult = optionalResult(_.redis.get(Write(key)))
  def set[A: Write, B: Write](key: A, value: B): ZIO[Redis, Nothing, Unit] = unitResult(_.redis.set(Write(key), Write(value)))
}
