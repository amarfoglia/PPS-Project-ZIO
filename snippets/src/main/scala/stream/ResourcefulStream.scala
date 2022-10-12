package stream

import zio.stream.{ZStream, UStream, Stream}
import zio.ZIOAppDefault
import zio.{ZIO, Console}

import scala.io.Source

val linesApp: ZStream[Any, Throwable, String] =
  ZStream
    .acquireReleaseWith(
      ZIO.attempt(Source.fromResource("stream_test.txt")) // acquire
    )(b => ZIO.succeed(b.close)) // release
    .flatMap(b => ZStream.fromIterator(b.getLines))

import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException

def application: ZStream[Any, IOException, Unit] =
  ZStream.fromZIO(
    Console.printLine("Application Logic.") <*
      ZIO.attempt(Files.createDirectory(Path.of("tmp")))
        .mapError(IOException(_))
  )

def deleteDir(dir: Path): ZIO[Any, IOException, Unit] =
  ZIO.attempt(Files.delete(dir))
    .mapError(IOException(_))

val dirApp: ZStream[Any, IOException, Any] =
  application ++ ZStream.finalizer(
    deleteDir(Path.of("tmp")).orDie
  ).ensuring(ZIO.debug("Doing some other works..."))


object ResourcefulStream extends ZIOAppDefault {

  override def run = linesApp.runForeach(Console.printLine(_)) *> dirApp.runDrain
}