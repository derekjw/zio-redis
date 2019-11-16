package zio.redis.serialization

import java.nio.charset.Charset

import zio.Chunk

import scala.collection.Factory
import scala.util.control.NonFatal

case class SerializationFailure(message: String, cause: Option[Throwable]) extends RuntimeException(message, cause.orNull)

trait Read[A] {
  def apply(chunk: Chunk[Byte]): Read.Result[A]
}

object Read {
  type Result[A] = Either[SerializationFailure, A]

  @inline
  def apply[A](chunk: Chunk[Byte])(implicit read: Read[A]): Result[A] = read(chunk)

  implicit val readIdentity: Read[Chunk[Byte]] = chunk => Right(chunk)

  implicit val readString: Read[String] = {
    val charset = Charset.forName("UTF8")

    { chunk =>
      try {
        Right(new String(chunk.toArray, charset))
      } catch {
        case NonFatal(e) => Left(SerializationFailure("Failed to parse as string", Some(e)))
      }
    }
  }
}

trait MultiRead[A] {
  def apply(chunks: Chunk[Chunk[Byte]]): Read.Result[A]
}

object MultiRead {
  @inline
  def apply[A](chunks: Chunk[Chunk[Byte]])(implicit read: MultiRead[A]): Read.Result[A] = read(chunks)

  implicit val multiReadIdentity: MultiRead[Chunk[Chunk[Byte]]] = chunks => Right(chunks)

  implicit def multiFromRead[M[_], A](implicit read: Read[A], factory: Factory[A, M[A]]): MultiRead[M[A]] = { chunks =>
    val builder = factory.newBuilder
    val failure = chunks.foldWhile(Option.empty[SerializationFailure])(_.isEmpty) { (_, chunk) =>
      read(chunk) match {
        case Right(value) =>
          builder.addOne(value)
          None
        case Left(failure) =>
          Some(failure)
      }
    }
    failure.toLeft(builder.result())
  }
}
