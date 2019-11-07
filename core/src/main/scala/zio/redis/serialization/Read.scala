package zio.redis.serialization

import zio.Chunk

case class SerializationFailure(message: String, cause: Option[Throwable])
    extends RuntimeException(message, cause.orNull)

trait Read[A] {
  def apply(chunk: Chunk[Byte]): Read.Result[A]
}

object Read {
  type Result[A] = Either[SerializationFailure, A]
}
