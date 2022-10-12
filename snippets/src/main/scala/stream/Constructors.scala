package stream

import zio.stream.{ZStream, UStream, Stream}
import zio.Console
import zio.ZIO
import zio.Chunk
import zio.Schedule
import zio.Random

lazy val numbers: UStream[Int] =
  ZStream.fromIterable(List(1, 2, 3, 4, 5))

lazy val hello: UStream[Unit] =
  ZStream.fromZIO(Console.print("Hello").orDie) ++
    ZStream.fromZIO(Console.print("World!").orDie)


def registerCallback(
  name: String,
  onEvent: Int => Unit,
  onError: Throwable => Unit
): Unit = ???

// Lifting an Asynchronous API to ZStream
val stream = ZStream.async[Any, Throwable, Int] { cb =>
  registerCallback(
    "foo",
    event => cb(ZIO.succeed(Chunk(event))),
    error => cb(ZIO.fail(error).mapError(Some(_)))
  )
}

val repeatZero: ZStream[Any, Nothing, Int] =
  ZStream.repeat(0)

import zio.durationInt

val repeatZeroEverySecond: ZStream[Any, Nothing, Int] =
  ZStream.repeatWithSchedule(0, Schedule.spaced(1.seconds))

val randomInts: ZStream[Any, Nothing, Int] =
  ZStream.repeatZIO(Random.nextInt)

def fromList[A](list: List[A]): ZStream[Any, Nothing, A] =
  ZStream.unfold(list) {
    case h :: t => Some((h, t))
    case Nil    => None
  }

import java.io.IOException
import java.io.FileInputStream

val stream1: Stream[IOException, Byte] =
  ZStream.fromInputStream(new FileInputStream("snippets/src/main/resources/stream_test.txt"))

val stream2: Stream[IOException, Byte] =
  ZStream.fromResource("stream_test.txt")

val stream3: Stream[Throwable, Int] =
  ZStream.fromJavaStream(java.util.stream.Stream.of(1, 2, 3))

import zio.ZIOAppDefault

object Constructors extends ZIOAppDefault {

  val merged = ZStream.mergeAllUnbounded(128)(
    numbers,
    hello,
    // repeatZero,
    // randomInts,
    repeatZeroEverySecond,
    fromList(List(1,2,3)),
    stream1,
    stream2,
    stream3,
  )

  override def run = merged.runForeach(Console.printLine(_))
}