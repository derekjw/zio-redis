package zio.redis

import zio.nio.SocketAddress
import zio.nio.channels.AsynchronousSocketChannel
import zio.redis.protocol.Constants._
import zio.redis.protocol.{Bytes, Done, Iteratee, Iteratees, RedisBulk, RedisString, RedisType}
import zio.redis.serialization.Write
import zio.stream.ZStream
import zio.{Chunk, IO, Managed, Promise, Queue, ZIO, ZManaged, ZSchedule}

import scala.annotation.tailrec

class RedisClient[R] private (writeQueue: Queue[Chunk[Byte]], responsesQueue: Queue[RedisClient.Response[_]], runner: ZIO[R, Exception, Nothing]) extends Redis.Service[R] {
  private def executeUnit(request: Chunk[Chunk[Byte]]): ZIO[R, Exception, Unit] =
    Promise.make[Nothing, Unit].flatMap(p => send(request, new RedisClient.UnitResponse(p)))
  private def executeBoolean(request: Chunk[Chunk[Byte]]): ZIO[R, Nothing, Boolean] = ???
  private def executeInt(request: Chunk[Chunk[Byte]]): ZIO[R, Nothing, Int] = ???
  private def executeOptional(request: Chunk[Chunk[Byte]]): ZIO[R, Exception, Option[Chunk[Byte]]] =
    Promise.make[Nothing, Option[Chunk[Byte]]].flatMap(p => send(request, new RedisClient.BulkResponse(p)))
  private def executeMulti(request: Chunk[Chunk[Byte]]): ZIO[R, Nothing, Chunk[Chunk[Byte]]] = ???

  private def send[A](request: Chunk[Chunk[Byte]], response: RedisClient.Response[A]) =
    (writeQueue.offer(format(request)) *> responsesQueue.offer(response) *> response.get).raceAttempt(runner)

  private def format(request: Chunk[Chunk[Byte]]): Chunk[Byte] = {
    val count = request.length
    request.fold(Chunk.fromArray(("*" + count).getBytes) ++ EOL) { (acc, bytes) =>
      acc ++ Chunk.fromArray(("$" + bytes.length).getBytes) ++ EOL ++ bytes ++ EOL
    }.materialize
  }

  def keys[A: Write](pattern: A): Redis.MultiValueResult[R] = Redis.MultiValueResult(executeMulti(Chunk(KEYS, Write(pattern))))
  def randomKey: Redis.OptionalResult[R] = Redis.OptionalResult(executeOptional(Chunk.single(RANDOMKEY)))
  def rename[A: Write, B: Write](oldKey: A, newKey: B): ZIO[R, Exception, Unit] = executeUnit(Chunk(RENAME, Write(oldKey), Write(newKey)))
  def renamenx[A: Write, B: Write](oldKey: A, newKey: B): ZIO[R, Nothing, Boolean] = executeBoolean(Chunk(RENAMENX, Write(oldKey), Write(newKey)))
  def dbsize: ZIO[R, Nothing, Int] = executeInt(Chunk.single(DBSIZE))
  def exists[A: Write](key: A): ZIO[R, Nothing, Boolean] = executeBoolean(Chunk(EXISTS, Write(key)))
  def del[A: Write](keys: Iterable[A]): ZIO[R, Nothing, Int] = executeInt(Chunk.single(DEL) ++ Chunk.fromArray(keys.view.map(Write(_)).toArray))
  def ping: ZIO[R, Exception, Unit] = executeUnit(Chunk.single(PING))
  def get[A: Write](key: A): Redis.OptionalResult[R] = Redis.OptionalResult(executeOptional(Chunk(GET, Write(key))))
  def set[A: Write, B: Write](key: A, value: B): ZIO[R, Exception, Unit] = executeUnit(Chunk(SET, Write(key), Write(value)))
}

object RedisClient {
  def apply[R](port: Int = 6379): ZManaged[R, Exception, Redis.Service[R]] = {
    for {
      channel <- managedChannel(port)
      readQueue <- Queue.bounded[Chunk[Byte]](64).toManaged_
      writeQueue <- Queue.bounded[Chunk[Byte]](64).toManaged_
      responsesQueue <- Queue.unbounded[Response[_]].toManaged_ // trigger failures for enqueued responses on release?
      readFiber <- channel.read(8192).flatMap(readQueue.offer).unit.repeat(ZSchedule.forever).fork.toManaged_
      writeFiber <- writeQueue.take.flatMap(channel.write).unit.repeat(ZSchedule.forever).fork.toManaged_
      responseFiber <- ZStream.fromQueue(readQueue).mapAccum(Iteratees.readResult)(parseResponse(_, _)).mapM(_.mapM_(bytes => responsesQueue.take.flatMap(_(bytes)))).runDrain.fork.toManaged_
    } yield new RedisClient[R](writeQueue, responsesQueue, readFiber.join.raceAttempt(writeFiber.join).raceAttempt(responseFiber.join).flatMap(_ => ZIO.never))
  }

  private def managedChannel(port: Int): Managed[Exception, AsynchronousSocketChannel] = for {
    channel <- AsynchronousSocketChannel()
    address <- SocketAddress.inetSocketAddress(port).toManaged_
    _ <- channel.connect(address).toManaged_
  } yield channel

  @tailrec
  def parseResponse(iteratee: Iteratee[RedisType], bytes: Chunk[Byte], results: Chunk[RedisType] = Chunk.empty): (Iteratee[RedisType], Chunk[RedisType]) = {
    iteratee(Bytes(bytes)) match {
      case (Done(redisType), Bytes(rest)) => parseResponse(Iteratees.readResult, rest, results ++ Chunk.single(redisType))
      case (Done(redisType), _) => (Iteratees.readResult, results ++ Chunk.single(redisType))
      case (cont, _) => (cont, results)
    }
  }

  private abstract class Response[T](promise: Promise[Nothing, T]) {
    def apply(redisType: RedisType): ZIO[Any, Nothing, Unit]
    def get: IO[Nothing, T] = promise.await
  }

  private class UnitResponse(promise: Promise[Nothing, Unit]) extends Response[Unit](promise) {
    def apply(redisType: RedisType): ZIO[Any, Nothing, Unit] = redisType match {
      case RedisString("OK") => promise.succeed(()).unit
      case _ => ZIO.unit
    }
  }

  private class BulkResponse(promise: Promise[Nothing, Option[Chunk[Byte]]]) extends Response[Option[Chunk[Byte]]](promise) {
    def apply(redisType: RedisType): ZIO[Any, Nothing, Unit] = redisType match {
      case RedisBulk(value) => promise.succeed(value).unit
      case _ => ZIO.unit
    }
  }
}
