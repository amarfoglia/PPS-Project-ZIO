package stream

import zio.ZIOAppDefault
import zio.stream.ZStream
import zio.ZIO
import zio.Console
import zio.Schedule
import zio.durationInt

object ErrorHandling {
  val s1 = ZStream(1, 2, 3) ++ ZStream.fail("MyError") ++ ZStream(4, 5)
  val s2 = ZStream(7, 8, 9)

  val s3 = s1.orElse(s2) // Output: 1, 2, 3, 7, 8, 9

  val s4 = s1.catchAll { case "MyError" => s2 } // Output: 1, 2, 3, 6, 7, 8

  val enterNumberApp =
    ZStream(1, 2, 3) ++
      ZStream
        .fromZIO(
          Console.print("Enter a number: ") *> Console.readLine
            .flatMap(x =>
              x.toIntOption match {
                case Some(value) => ZIO.succeed(value)
                case None        => ZIO.fail("NaN")
              }
            )
        )
        .retry(Schedule.exponential(1.second))
}

import ErrorHandling._

object Main extends ZIOAppDefault {

  override def run =
    s3.foreach(ZIO.debug(_)) *>
      s4.foreach(ZIO.debug(_)) *>
      enterNumberApp.runForeach(ZIO.debug(_))
}
