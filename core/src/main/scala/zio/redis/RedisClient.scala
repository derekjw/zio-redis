package zio.redis

import java.nio.channels.{AsynchronousCloseException, ClosedChannelException}

import zio.nio.SocketAddress
import zio.nio.channels.AsynchronousSocketChannel
import zio.redis.protocol.Constants._
import zio.redis.protocol.{Bytes, Done, Iteratee, Iteratees, RedisBulk, RedisString, RedisType}
import zio.redis.serialization.Write
import zio.stream.ZStream
import zio.{Chunk, IO, Managed, Promise, Queue, ZIO, ZSchedule}

import scala.annotation.tailrec

class RedisClient private (writeQueue: Queue[(Chunk[Byte], RedisClient.Response[_])]) extends Redis.Service[Any] {
  private def executeUnit(request: Chunk[Chunk[Byte]]): IO[Exception, Unit] =
    Promise.make[Exception, Unit].flatMap(p => send(request, new RedisClient.UnitResponse(p)))
  private def executeBoolean(request: Chunk[Chunk[Byte]]): IO[Nothing, Boolean] = ???
  private def executeInt(request: Chunk[Chunk[Byte]]): IO[Nothing, Int] = ???
  private def executeOptional(request: Chunk[Chunk[Byte]]): IO[Exception, Option[Chunk[Byte]]] =
    Promise.make[Exception, Option[Chunk[Byte]]].flatMap(p => send(request, new RedisClient.BulkResponse(p)))
  private def executeMulti(request: Chunk[Chunk[Byte]]): IO[Nothing, Chunk[Chunk[Byte]]] = ???

  private def send[A](request: Chunk[Chunk[Byte]], response: RedisClient.Response[A]): IO[Exception, A] =
    writeQueue.offer((format(request), response)) *> response.get

  private def format(request: Chunk[Chunk[Byte]]): Chunk[Byte] = {
    val count = request.length
    request
      .fold(Chunk.fromArray(("*" + count).getBytes) ++ EOL) { (acc, bytes) =>
        acc ++ Chunk.fromArray(("$" + bytes.length).getBytes) ++ EOL ++ bytes ++ EOL
      }
      .materialize
  }

  def keys[A: Write](pattern: A): Redis.MultiValueResult[Any] = Redis.MultiValueResult(executeMulti(Chunk(KEYS, Write(pattern))))
  def randomKey: Redis.OptionalResult[Any] = Redis.OptionalResult(executeOptional(Chunk.single(RANDOMKEY)))
  def rename[A: Write, B: Write](oldKey: A, newKey: B): IO[Exception, Unit] = executeUnit(Chunk(RENAME, Write(oldKey), Write(newKey)))
  def renamenx[A: Write, B: Write](oldKey: A, newKey: B): IO[Nothing, Boolean] = executeBoolean(Chunk(RENAMENX, Write(oldKey), Write(newKey)))
  def dbsize: IO[Nothing, Int] = executeInt(Chunk.single(DBSIZE))
  def exists[A: Write](key: A): IO[Nothing, Boolean] = executeBoolean(Chunk(EXISTS, Write(key)))
  def del[A: Write](keys: Iterable[A]): IO[Nothing, Int] = executeInt(Chunk.single(DEL) ++ Chunk.fromArray(keys.view.map(Write(_)).toArray))
  def ping: IO[Exception, Unit] = executeUnit(Chunk.single(PING))
  def get[A: Write](key: A): Redis.OptionalResult[Any] = Redis.OptionalResult(executeOptional(Chunk(GET, Write(key))))
  def set[A: Write, B: Write](key: A, value: B): IO[Exception, Unit] = executeUnit(Chunk(SET, Write(key), Write(value)))
}

object RedisClient {
  // TODO: Handle connection error
  def apply(port: Int = 6379): Managed[Exception, Redis.Service[Any]] =
    for {
      channel <- managedChannel(port)
      writeQueue <- Queue.bounded[(Chunk[Byte], Response[_])](32).toManaged_
      responsesQueue <- Queue.unbounded[Response[_]].toManaged_ // trigger failures for enqueued responses on release?
      _ <- writeLoop(writeQueue, responsesQueue, channel.write).fork.toManaged_
      reader = channel.read(8192).catchSome { case _: AsynchronousCloseException | _: ClosedChannelException => ZIO.succeed(Chunk.empty) }
      _ <- ZStream
        .fromEffect(reader)
        .repeat(ZSchedule.forever)
        .mapAccum(Iteratees.readResult)(parseResponse(_, _))
        .foreach(_.mapM_(bytes => responsesQueue.take.flatMap(_(bytes))))
        .fork
        .toManaged_
    } yield new RedisClient(writeQueue)

  private def managedChannel(port: Int): Managed[Exception, AsynchronousSocketChannel] =
    for {
      channel <- AsynchronousSocketChannel()
      address <- SocketAddress.inetSocketAddress("localhost", port).toManaged_
      _ <- channel.connect(address).toManaged_
    } yield channel

  @tailrec
  def parseResponse(iteratee: Iteratee[RedisType], bytes: Chunk[Byte], results: Chunk[RedisType] = Chunk.empty): (Iteratee[RedisType], Chunk[RedisType]) =
    iteratee(Bytes(bytes)) match {
      case (Done(redisType), Bytes(rest)) => parseResponse(Iteratees.readResult, rest, results ++ Chunk.single(redisType))
      case (Done(redisType), _)           => (Iteratees.readResult, results ++ Chunk.single(redisType))
      case (cont, _)                      => (cont, results)
    }

  private abstract class Response[T](promise: Promise[Exception, T]) {
    def apply(redisType: RedisType): ZIO[Any, Nothing, Unit]
    def get: IO[Exception, T] = promise.await
  }

  private class UnitResponse(promise: Promise[Exception, Unit]) extends Response[Unit](promise) {
    def apply(redisType: RedisType): ZIO[Any, Nothing, Unit] = redisType match {
      case RedisString("OK") => promise.succeed(()).unit
      case _                 => promise.fail(new RuntimeException("Invalid RedisType")).unit
    }
  }

  private class BulkResponse(promise: Promise[Exception, Option[Chunk[Byte]]]) extends Response[Option[Chunk[Byte]]](promise) {
    def apply(redisType: RedisType): ZIO[Any, Nothing, Unit] = redisType match {
      case RedisBulk(value) => promise.succeed(value).unit
      case _                => promise.fail(new RuntimeException("Invalid RedisType")).unit
    }
  }

  private def writeLoop(writingQueue: Queue[(Chunk[Byte], Response[_])], responseQueue: Queue[Response[_]], write: Chunk[Byte] => IO[Exception, Int]): IO[Exception, Unit] = {
    val dequeue = writingQueue.take.flatMap { first =>
      writingQueue.takeAll.flatMap { rest =>
        val (writes, responses) = (first :: rest).unzip
        responseQueue.offerAll(responses).as(writes.reduce(_ ++ _))
      }
    }

    def run(ready: Chunk[Byte]): IO[Exception, Unit] =
      if (ready.nonEmpty) {
        write(ready).fork >>= (dequeue <* _.join) >>= run
      } else {
        dequeue >>= run
      }

    run(Chunk.empty)
  }

}
