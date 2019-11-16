package zio.redis

import java.nio.channels.{AsynchronousCloseException, ClosedChannelException}

import zio.nio.SocketAddress
import zio.nio.channels.AsynchronousSocketChannel
import zio.redis.protocol.Constants._
import zio.redis.protocol.{Bytes, Done, Iteratee, Iteratees, RedisBulk, RedisInteger, RedisMulti, RedisString, RedisType}
import zio.redis.serialization.Write
import zio.stream.ZStream
import zio.{Chunk, IO, Managed, Promise, Queue, ZIO, ZSchedule, redis}

import scala.annotation.tailrec

class RedisClient private (writeQueue: Queue[(Chunk[Byte], RedisClient.Response[_])]) extends Redis.Service[Any] {
  private def executeUnit(request: Chunk[Chunk[Byte]]): IO[RedisClientFailure, Unit] =
    Promise.make[UnexpectedResponse, Unit].flatMap(p => send(request, new RedisClient.UnitResponse(p)))
  private def executeBoolean(request: Chunk[Chunk[Byte]]): IO[RedisClientFailure, Boolean] =
    Promise.make[UnexpectedResponse, Boolean].flatMap(p => send(request, new RedisClient.BooleanResponse(p)))
  private def executeInteger(request: Chunk[Chunk[Byte]]): IO[RedisClientFailure, Long] =
    Promise.make[UnexpectedResponse, Long].flatMap(p => send(request, new RedisClient.IntegerResponse(p)))
  private def executeOptional(request: Chunk[Chunk[Byte]]): IO[RedisClientFailure, Option[Chunk[Byte]]] =
    Promise.make[UnexpectedResponse, Option[Chunk[Byte]]].flatMap(p => send(request, new RedisClient.BulkResponse(p)))
  private def executeMultiBulk(request: Chunk[Chunk[Byte]]): IO[RedisClientFailure, Chunk[Chunk[Byte]]] =
    Promise.make[UnexpectedResponse, Chunk[Chunk[Byte]]].flatMap(p => send(request, new redis.RedisClient.MultiBulkResponse(p)))

  private def send[A](request: Chunk[Chunk[Byte]], response: RedisClient.Response[A]): IO[RedisClientFailure, A] =
    writeQueue.offer((format(request), response)) *> response.get

  private def format(request: Chunk[Chunk[Byte]]): Chunk[Byte] = {
    val count = request.length
    request
      .fold(Chunk.fromArray(("*" + count).getBytes) ++ EOL) { (acc, bytes) =>
        acc ++ Chunk.fromArray(("$" + bytes.length).getBytes) ++ EOL ++ bytes ++ EOL
      }
      .materialize
  }

  def keys[A: Write](pattern: A): Redis.MultiValueResult[Any] = Redis.MultiValueResult(executeMultiBulk(Chunk(KEYS, Write(pattern))))
  def randomKey: Redis.OptionalResult[Any] = Redis.OptionalResult(executeOptional(Chunk.single(RANDOMKEY)))
  def rename[A: Write, B: Write](oldKey: A, newKey: B): IO[RedisClientFailure, Unit] = executeUnit(Chunk(RENAME, Write(oldKey), Write(newKey)))
  def renamenx[A: Write, B: Write](oldKey: A, newKey: B): IO[RedisClientFailure, Boolean] = executeBoolean(Chunk(RENAMENX, Write(oldKey), Write(newKey)))
  def dbsize: IO[RedisClientFailure, Long] = executeInteger(Chunk.single(DBSIZE))
  def exists[A: Write](keys: Iterable[A]): IO[RedisClientFailure, Long] = executeInteger(Chunk.single(EXISTS) ++ Chunk.fromArray(keys.view.map(Write(_)).toArray))
  def del[A: Write](keys: Iterable[A]): IO[RedisClientFailure, Long] = executeInteger(Chunk.single(DEL) ++ Chunk.fromArray(keys.view.map(Write(_)).toArray))
  def ping: IO[RedisClientFailure, Unit] = executeUnit(Chunk.single(PING))
  def get[A: Write](key: A): Redis.OptionalResult[Any] = Redis.OptionalResult(executeOptional(Chunk(GET, Write(key))))
  def set[A: Write, B: Write](key: A, value: B): IO[RedisClientFailure, Unit] = executeUnit(Chunk(SET, Write(key), Write(value)))
}

object RedisClient {
  // TODO: Handle connection error
  def apply(port: Int = 6379, writeQueueSize: Int = 32): Managed[ConnectionFailure, Redis.Service[Any]] =
    for {
      channel <- managedChannel(port)
      writeQueue <- Queue.bounded[(Chunk[Byte], Response[_])](writeQueueSize).toManaged_
      responsesQueue <- Queue.unbounded[Response[_]].toManaged_ // trigger failures for enqueued responses on release?
      _ <- writeLoop(writeQueue, responsesQueue, channel.write(_).mapError(e => ConnectionFailure("Failed to write", Some(e)))).fork.toManaged_
      reader = channel.read(8192).catchSome { case _: AsynchronousCloseException | _: ClosedChannelException => ZIO.succeed(Chunk.empty) }
      _ <- ZStream
        .fromEffect(reader)
        .repeat(ZSchedule.forever)
        .mapAccum(Iteratees.readResult)(parseResponse(_, _))
        .foreach(_.mapM_(bytes => responsesQueue.take.flatMap(_(bytes))))
        .fork
        .toManaged_
    } yield new RedisClient(writeQueue)

  private def managedChannel(port: Int): Managed[ConnectionFailure, AsynchronousSocketChannel] =
    for {
      channel <- AsynchronousSocketChannel().mapError(e => ConnectionFailure(s"Unable to open socket", Some(e)))
      host = "localhost"
      address <- SocketAddress.inetSocketAddress(host, port).mapError(e => ConnectionFailure(s"Unable to create address: $host:$port", Some(e))).toManaged_
      _ <- channel.connect(address).mapError(e => ConnectionFailure(s"Unable to connect to $address", Some(e))).toManaged_
    } yield channel

  @tailrec
  def parseResponse(iteratee: Iteratee[RedisType], bytes: Chunk[Byte], results: Chunk[RedisType] = Chunk.empty): (Iteratee[RedisType], Chunk[RedisType]) =
    iteratee(Bytes(bytes)) match {
      case (Done(redisType), Bytes(rest)) => parseResponse(Iteratees.readResult, rest, results ++ Chunk.single(redisType))
      case (Done(redisType), _)           => (Iteratees.readResult, results ++ Chunk.single(redisType))
      case (cont, _)                      => (cont, results)
    }

  private abstract class Response[T](promise: Promise[UnexpectedResponse, T]) {
    def apply(redisType: RedisType): ZIO[Any, Nothing, Unit]
    def get: IO[UnexpectedResponse, T] = promise.await
  }

  private class UnitResponse(promise: Promise[UnexpectedResponse, Unit]) extends Response[Unit](promise) {
    def apply(redisType: RedisType): ZIO[Any, Nothing, Unit] = redisType match {
      case RedisString("OK") => promise.succeed(()).unit
      case _                 => promise.fail(UnexpectedResponse("Invalid RedisType")).unit
    }
  }

  private class BooleanResponse(promise: Promise[UnexpectedResponse, Boolean]) extends Response[Boolean](promise) {
    def apply(redisType: RedisType): ZIO[Any, Nothing, Unit] = redisType match {
      case RedisInteger(1) => promise.succeed(true).unit
      case RedisInteger(0) => promise.succeed(false).unit
      case _               => promise.fail(UnexpectedResponse("Invalid RedisType")).unit
    }
  }

  private class IntegerResponse(promise: Promise[UnexpectedResponse, Long]) extends Response[Long](promise) {
    def apply(redisType: RedisType): ZIO[Any, Nothing, Unit] = redisType match {
      case RedisInteger(value) => promise.succeed(value).unit
      case _                   => promise.fail(UnexpectedResponse("Invalid RedisType")).unit
    }
  }

  private class BulkResponse(promise: Promise[UnexpectedResponse, Option[Chunk[Byte]]]) extends Response[Option[Chunk[Byte]]](promise) {
    def apply(redisType: RedisType): ZIO[Any, Nothing, Unit] = redisType match {
      case RedisBulk(value) => promise.succeed(value).unit
      case _                => promise.fail(UnexpectedResponse("Invalid RedisType")).unit
    }
  }

  private class MultiBulkResponse(promise: Promise[UnexpectedResponse, Chunk[Chunk[Byte]]]) extends Response[Chunk[Chunk[Byte]]](promise) {
    def apply(redisType: RedisType): ZIO[Any, Nothing, Unit] = redisType match {
      case RedisMulti(multiValue) =>
        promise.completeWith {
          multiValue.getOrElse(Chunk.empty).mapM {
            case RedisBulk(value) => ZIO.succeed(value.getOrElse(Chunk.empty))
            case _                => ZIO.fail(UnexpectedResponse("Invalid RedisType"))
          }
        }.unit
    }
  }

  private def writeLoop(writingQueue: Queue[(Chunk[Byte], Response[_])], responseQueue: Queue[Response[_]], write: Chunk[Byte] => IO[ConnectionFailure, Int]): IO[ConnectionFailure, Unit] = {
    val dequeue = writingQueue.take.flatMap { first =>
      writingQueue.takeAll.flatMap { rest =>
        val (writes, responses) = (first :: rest).unzip
        responseQueue.offerAll(responses).as(writes.reduce(_ ++ _))
      }
    }

    def run(ready: Chunk[Byte]): IO[ConnectionFailure, Unit] =
      if (ready.nonEmpty) {
        write(ready).fork >>= (dequeue <* _.join) >>= run
      } else {
        dequeue >>= run
      }

    run(Chunk.empty)
  }

}
