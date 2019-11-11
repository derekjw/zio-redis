package zio.redis
package protocol

import zio.Chunk
import zio.redis.protocol.Iteratee.Chain
import zio.redis.serialization.Read

import scala.annotation.tailrec
import scala.collection.immutable.Queue

case class RedisProtocolException(str: String) extends RuntimeException(str)

// TEMPORARY, replace with something a bit less archaic?
object Iteratees {
  import Constants.EOL

  final val readUntilEOL = Iteratee.takeUntil(EOL)

  final val readType = (_: Chunk[Byte])(0) match {
    case 43 /* '+' */ => readString
    case 45 /* '-' */ => readError
    case 58 /* ':' */ => readInteger
    case 36 /* '$' */ => readBulk
    case 42 /* '*' */ => readMulti
    case x            => Failure(RedisProtocolException("Invalid result type: " + x))
  }

  final val readResult: Iteratee[RedisType] = Iteratee.take(1).flatMap(readType)

  final val bytesToString = (bytes: Chunk[Byte]) => RedisString(Read[String](bytes).fold(throw _, identity))
  final val readString: Iteratee[RedisString] = readUntilEOL.map(bytesToString)

  final val bytesToError = (bytes: Chunk[Byte]) => RedisError(Read[String](bytes).fold(throw _, identity))
  final val readError: Iteratee[RedisError] = readUntilEOL.map(bytesToError)

  final val bytesToInteger = (bytes: Chunk[Byte]) => RedisInteger(Read[String](bytes).fold(throw _, _.toLong))
  final val readInteger: Iteratee[RedisInteger] = readUntilEOL.map(bytesToInteger)

  final val notFoundBulk = Done(RedisBulk.notFound)
  final val emptyBulk = Done(RedisBulk.empty)
  final val bytesToBulk = (bytes: Chunk[Byte]) =>
    Read[String](bytes).fold(throw _, _.toInt) match {
      case -1 => notFoundBulk
      case 0  => emptyBulk
      case n =>
        for {
          bytes <- Iteratee.take(n)
          _ <- readUntilEOL
        } yield RedisBulk(Some(bytes))
  }
  final val readBulk: Iteratee[RedisBulk] = readUntilEOL.flatMap(bytesToBulk)

  final val notFoundMulti = Done(RedisMulti.notFound)
  final val emptyMulti = Done(RedisMulti.empty)
  final val bytesToMulti = (bytes: Chunk[Byte]) =>
    Read[String](bytes).fold(throw _, _.toInt) match {
      case -1 => notFoundMulti
      case 0  => emptyMulti
      case n  => Iteratee.takeList(n)(readResult).map(x => RedisMulti(Some(x)))
  }
  final val readMulti: Iteratee[RedisMulti] = readUntilEOL.flatMap(bytesToMulti)

}

object Iteratee {

  /**
    * An Iteratee that returns a ByteString of the requested length.
    */
  def take(length: Int): Iteratee[Chunk[Byte]] = {
    def step(taken: Chunk[Byte])(input: Input): (Iteratee[Chunk[Byte]], Input) = input match {
      case Bytes(more) =>
        val bytes = taken ++ more
        if (bytes.length >= length)
          (Done(bytes.take(length)), Bytes(bytes.drop(length)))
        else
          (Cont(step(bytes)), Bytes.empty)
      case eof => (Cont(step(taken)), eof)
    }

    Cont(step(Chunk.empty))
  }

  /**
    * An Iteratee that returns the ByteString prefix up until the supplied delimiter.
    * The delimiter is dropped by default, but it can be returned with the result by
    * setting 'inclusive' to be 'true'.
    */
  def takeUntil(delimiter: Chunk[Byte], inclusive: Boolean = false): Iteratee[Chunk[Byte]] = {
    def step(taken: Chunk[Byte])(input: Input): (Iteratee[Chunk[Byte]], Input) = input match {
      case Bytes(more) =>
        val bytes = taken ++ more
        var idx = math.max(taken.length - delimiter.length, 0)
        var startIdx = -1
        // TODO: Can this be cleaned up?
        while (startIdx == -1 && idx + delimiter.length <= bytes.length) {
          if (bytes(idx) == delimiter(0)) {
            val taken = bytes.drop(idx).take(delimiter.length)
            if (taken == delimiter) {
              startIdx = idx
            }
          }
          idx += 1
        }
        if (startIdx >= 0) {
          val endIdx = startIdx + delimiter.length
          (Done(bytes.take(if (inclusive) endIdx else startIdx)), Bytes(bytes.drop(endIdx)))
        } else {
          (Cont(step(bytes)), Bytes.empty)
        }
      case eof => (Cont(step(taken)), eof)
    }

    Cont(step(Chunk.empty))
  }

  def takeList[A](length: Int)(iter: Iteratee[A]): Iteratee[Chunk[A]] = {
    def step(left: Int, list: Chunk[A]): Iteratee[Chunk[A]] =
      if (left == 0) Done(list)
      else iter.flatMap(a => step(left - 1, list + a))

    step(length, Chunk.empty)
  }

  // private api

  private object Chain {
    def apply[A](f: Input => (Iteratee[A], Input)) = new Chain[A](f, Queue.empty)
    def apply[A, B](f: Input => (Iteratee[A], Input), k: A => Iteratee[B]) = new Chain[B](f, Queue(k.asInstanceOf[Any => Iteratee[Any]]))
  }

  /**
    * A function 'ByteString => Iteratee[A]' that composes with 'A => Iteratee[B]' functions
    * in a stack-friendly manner.
    *
    * For internal use within Iteratee.
    */
  private final case class Chain[A] private (cur: Input => (Iteratee[Any], Input), queue: Queue[Any => Iteratee[Any]]) extends (Input => (Iteratee[A], Input)) {

    def :+[B](f: A => Iteratee[B]) = new Chain[B](cur, queue.enqueue(f.asInstanceOf[Any => Iteratee[Any]]))

    def apply(input: Input): (Iteratee[A], Input) = {
      @tailrec
      def run(result: (Iteratee[Any], Input), queue: Queue[Any => Iteratee[Any]]): (Iteratee[Any], Input) =
        if (queue.isEmpty) result
        else
          result match {
            case (Done(value), rest) =>
              val (head, tail) = queue.dequeue
              run(head(value)(rest), tail)
            case (Cont(f), rest) =>
              (Cont(Chain(f, queue)), rest)
            case _ => result
          }
      run(cur(input), queue).asInstanceOf[(Iteratee[A], Input)]
    }
  }

}

sealed trait Input {
  def ++(that: Input): Input
}

object Bytes {
  val empty = Bytes(Chunk.empty)
}

case class Bytes(value: Chunk[Byte]) extends Input {
  def ++(that: Input) = that match {
    case Bytes(more) => Bytes(value ++ more)
    case _: EOF      => that
  }
}

case class EOF(cause: Option[Exception]) extends Input {
  def ++(that: Input) = this
}

/**
  * A basic Iteratee implementation of Oleg's Iteratee (http://okmij.org/ftp/Streams.html).
  * No support for Enumerator or Input types other then ByteString at the moment.
  */
sealed abstract class Iteratee[+A] {

  /**
    * Applies the given input to the Iteratee, returning the resulting Iteratee
    * and the unused Input.
    */
  final def apply(input: Input): (Iteratee[A], Input) = this match {
    case Cont(f) => f(input)
    case iter    => (iter, input)
  }

  final def get: A = this(EOF(None))._1 match {
    case Done(value) => value
    case Cont(_)     => sys.error("Divergent Iteratee")
    case Failure(e)  => throw e
  }

  final def flatMap[B](f: A => Iteratee[B]): Iteratee[B] = this match {
    case Done(value)       => f(value)
    case Cont(k: Chain[_]) => Cont(k :+ f)
    case Cont(k)           => Cont(Chain(k, f))
    case failure: Failure  => failure
  }

  final def map[B](f: A => B): Iteratee[B] = this match {
    case Done(value)       => Done(f(value))
    case Cont(k: Chain[_]) => Cont(k :+ ((a: A) => Done(f(a))))
    case Cont(k)           => Cont(Chain(k, (a: A) => Done(f(a))))
    case failure: Failure  => failure
  }

}

/**
  * An Iteratee representing a result and the remaining ByteString. Also used to
  * wrap any constants or precalculated values that need to be composed with
  * other Iteratees.
  */
final case class Done[+A](result: A) extends Iteratee[A]

/**
  * An Iteratee that still requires more input to calculate it's result.
  */
final case class Cont[+A](f: Input => (Iteratee[A], Input)) extends Iteratee[A]

/**
  * An Iteratee representing a failure to calcualte a result.
  * FIXME: move into 'Cont' as in Oleg's implementation
  */
final case class Failure(exception: Throwable) extends Iteratee[Nothing]
