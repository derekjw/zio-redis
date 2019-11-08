package zio.redis

import zio.redis.serialization.{Read, SerializationFailure}
import zio.{Chunk, ZIO}

trait Redis {
  val redis: Redis.Service[Any]
}

object Redis extends commands.Basic {
  trait Service[R] {
    private[redis] def executeUnit(request: Chunk[Chunk[Byte]]): ZIO[R, Nothing, Unit]
    private[redis] def executeBoolean(request: Chunk[Chunk[Byte]]): ZIO[R, Nothing, Boolean]
    private[redis] def executeInt(request: Chunk[Chunk[Byte]]): ZIO[R, Nothing, Int]
    private[redis] def executeOptional(request: Chunk[Chunk[Byte]]): ZIO[R, Nothing, Option[Chunk[Byte]]]
  }

  private[redis] def executeUnit(request: Chunk[Chunk[Byte]]): ZIO[Redis, Nothing, Unit] = ZIO.environment[Redis].flatMap(_.redis.executeUnit(request))
  private[redis] def executeBoolean(request: Chunk[Chunk[Byte]]): ZIO[Redis, Nothing, Boolean] = ZIO.environment[Redis].flatMap(_.redis.executeBoolean(request))
  private[redis] def executeInt(request: Chunk[Chunk[Byte]]): ZIO[Redis, Nothing, Int] = ZIO.environment[Redis].flatMap(_.redis.executeInt(request))
  private[redis] def executeOptional(request: Chunk[Chunk[Byte]]): ZIO[Redis, Nothing, Option[Chunk[Byte]]] = ZIO.environment[Redis].flatMap(_.redis.executeOptional(request))
  private[redis] def executeMulti(request: Chunk[Chunk[Byte]]): ZIO[Redis, Nothing, Chunk[Chunk[Byte]]] = ??? //ZIO.environment[Redis].flatMap(_.redis.executeSingle(request))

  final class OptionalResult(request: Chunk[Chunk[Byte]]) {
    def unit: ZIO[Redis, Nothing, Unit] = bytes.unit
    def bytes: ZIO[Redis, Nothing, Option[Chunk[Byte]]] = executeOptional(request)
    def as[A: Read]: ZIO[Redis, SerializationFailure, Option[A]] = bytes.flatMap {
      case Some(chunk) =>
        Read(chunk) match {
          case Right(value) => ZIO.some(value)
          case Left(failure) => ZIO.fail(failure)
        }
      case None => ZIO.none
    }
  }

  final class MultiValueResult(request: Chunk[Chunk[Byte]]) {
    def unit: ZIO[Redis, Nothing, Unit] = bytes.unit
    def bytes: ZIO[Redis, Nothing, Chunk[Chunk[Byte]]] = executeMulti(request)
    def as[A: Read]: ZIO[Redis, SerializationFailure, Chunk[A]] = ??? //bytes.flatMap(chunk => ZIO.fromEither(Read(chunk)))
  }
}
