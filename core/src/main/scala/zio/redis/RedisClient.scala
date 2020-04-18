package zio.redis

import zio.logging.{LogAnnotation, Logging}
import zio.nio.core.SocketAddress
import zio.nio.channels.AsynchronousSocketChannel
import zio.redis.protocol.Constants._
import zio.redis.protocol.{Bytes, Done, Iteratee, Iteratees, RedisBulk, RedisInteger, RedisMulti, RedisString, RedisType}
import zio.redis.serialization.Write
import zio.stream.ZStream
import zio.{Chunk, Fiber, IO, Managed, Promise, Queue, Schedule, ZIO, ZLayer, ZManaged, redis}

import scala.annotation.tailrec

class RedisClient private (writeQueue: Queue[(Chunk[Byte], RedisClient.Response[_])]) extends Redis.Service {
  private def executeUnit(request: Chunk[Chunk[Byte]]): IO[RedisClientFailure, Unit] =
    Promise.make[RedisClientFailure, Unit].flatMap(p => send(request, new RedisClient.UnitResponse(p)))
  private def executeBoolean(request: Chunk[Chunk[Byte]]): IO[RedisClientFailure, Boolean] =
    Promise.make[RedisClientFailure, Boolean].flatMap(p => send(request, new RedisClient.BooleanResponse(p)))
  private def executeInteger(request: Chunk[Chunk[Byte]]): IO[RedisClientFailure, Long] =
    Promise.make[RedisClientFailure, Long].flatMap(p => send(request, new RedisClient.IntegerResponse(p)))
  private def executeOptional(request: Chunk[Chunk[Byte]]): IO[RedisClientFailure, Option[Chunk[Byte]]] =
    Promise.make[RedisClientFailure, Option[Chunk[Byte]]].flatMap(p => send(request, new RedisClient.BulkResponse(p)))
  private def executeMultiBulk(request: Chunk[Chunk[Byte]]): IO[RedisClientFailure, Chunk[Chunk[Byte]]] =
    Promise.make[RedisClientFailure, Chunk[Chunk[Byte]]].flatMap(p => send(request, new redis.RedisClient.MultiBulkResponse(p)))

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
  def apply(host: String = "localhost", port: Int = 6379, writeQueueSize: Int = 32): ZManaged[Logging, ConnectionFailure, Redis.Service] = {
    val redisClient = for {
      _ <- Logging.info("Starting").toManaged(_ => Logging.info("Shutdown"))
      channel <- managedChannel(host, port)
      writeQueue <- Queue.bounded[(Chunk[Byte], Response[_])](writeQueueSize).toManaged(_.shutdown)
      responsesQueue <- Queue.unbounded[Response[_]].toManaged(_.shutdown)
      _ <- (Fiber.fiberName.set(Some("Redis Writer")) *> writeLoop(writeQueue, responsesQueue, channel.write(_).mapError(e => ConnectionFailure("Failed to write", Some(e))))).toManaged_.fork
        .mapM(_.disown) // FIXME: better fiber management
      reader = channel.read(8192).catchAll(e => ZIO.fail(ConnectionFailure("Failed to read", Some(e))))
      _ <- (Fiber.fiberName.set(Some("Redis Reader")) *> ZStream
        .fromEffect(reader)
        .repeat(Schedule.forever)
        .mapAccum(Iteratees.readResult)(parseResponse(_, _))
        .foreach(_.mapM_(bytes => responsesQueue.take.flatMap(_(bytes)))))
        .catchAll(e => ZStream.fromQueue(responsesQueue).mapM(_.fail(e)).runDrain)
        .toManaged_
        .fork
        .mapM(_.disown) // FIXME: better fiber management
    } yield new RedisClient(writeQueue)

    ZIO.access[Logging](_.get.derive(LogAnnotation.Name("Redis" :: "Client" :: Nil))).toManaged_.flatMap { logger =>
      redisClient.provideSomeLayer(ZLayer.succeed(logger))
    }
  }

  private def managedChannel(host: String, port: Int): Managed[ConnectionFailure, AsynchronousSocketChannel] =
    for {
      channel <- AsynchronousSocketChannel().mapError(e => ConnectionFailure(s"Unable to open socket", Some(e)))
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

  private abstract class Response[T](promise: Promise[RedisClientFailure, T]) {
    def apply(redisType: RedisType): ZIO[Any, Nothing, Unit]
    def get: IO[RedisClientFailure, T] = promise.await
    def fail(cause: RedisClientFailure): ZIO[Any, Nothing, Unit] = promise.fail(cause).unit
  }

  private class UnitResponse(promise: Promise[RedisClientFailure, Unit]) extends Response[Unit](promise) {
    def apply(redisType: RedisType): ZIO[Any, Nothing, Unit] = redisType match {
      case RedisString("OK") => promise.succeed(()).unit
      case _                 => promise.fail(UnexpectedResponse("Invalid RedisType")).unit
    }
  }

  private class BooleanResponse(promise: Promise[RedisClientFailure, Boolean]) extends Response[Boolean](promise) {
    def apply(redisType: RedisType): ZIO[Any, Nothing, Unit] = redisType match {
      case RedisInteger(1) => promise.succeed(true).unit
      case RedisInteger(0) => promise.succeed(false).unit
      case _               => promise.fail(UnexpectedResponse("Invalid RedisType")).unit
    }
  }

  private class IntegerResponse(promise: Promise[RedisClientFailure, Long]) extends Response[Long](promise) {
    def apply(redisType: RedisType): ZIO[Any, Nothing, Unit] = redisType match {
      case RedisInteger(value) => promise.succeed(value).unit
      case _                   => promise.fail(UnexpectedResponse("Invalid RedisType")).unit
    }
  }

  private class BulkResponse(promise: Promise[RedisClientFailure, Option[Chunk[Byte]]]) extends Response[Option[Chunk[Byte]]](promise) {
    def apply(redisType: RedisType): ZIO[Any, Nothing, Unit] = redisType match {
      case RedisBulk(value) => promise.succeed(value).unit
      case _                => promise.fail(UnexpectedResponse("Invalid RedisType")).unit
    }
  }

  private class MultiBulkResponse(promise: Promise[RedisClientFailure, Chunk[Chunk[Byte]]]) extends Response[Chunk[Chunk[Byte]]](promise) {
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
      writingQueue.takeAll.map { rest =>
        val (writes, responses) = (first :: rest).unzip
        (writes.reduce(_ ++ _).materialize, responses)
      }
    }

    def run(ready: (Chunk[Byte], List[Response[_]])): IO[ConnectionFailure, Unit] =
      if (ready._1.nonEmpty) {
        val writeAction = write(ready._1).foldM(failure => ZIO.foreach_(ready._2)(_.fail(failure)), _ => responseQueue.offerAll(ready._2))
        writeAction.fork >>= (dequeue <* _.join) >>= run
      } else {
        dequeue >>= run
      }

    run((Chunk.empty, Nil))
  }

}
