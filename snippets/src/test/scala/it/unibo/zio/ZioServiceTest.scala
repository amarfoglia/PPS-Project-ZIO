package it.unibo.zio

import zio._
import concurrent.duration.DurationInt
import zio.test._
import zio.test.Assertion._

object ZioServiceTest extends ZIOSpecDefault {

  val greet: Task[Unit] =
    for {
      name <- Console.readLine.orDie
      _    <- Console.printLine(s"Hello, $name!").orDie
    } yield ()

  val goShopping: ZIO[Any, Nothing, Unit] =
    Console.printLine("Going shopping!").orDie.delay(1.hour)

  def spec =
    suite("ExampleSpec")(
      test("ZIO.succeed must be equal to 2") {
        assertZIO(ZIO.succeed(1 + 1))(equalTo(2))
      },
      suite("MapSpec")(
        test("testing an effect using map operator") {
          ZIO.succeed(1 + 1).map(n => assert(n)(equalTo(2)))
        },
        test("testing an effect using a for comprehension") {
          for {
            n <- ZIO.succeed(1 + 1)
          } yield assert(n)(equalTo(2))
        }
      ),
      suite("FailSpec")(
        test("fails") {
          for {
            exit <- ZIO.attempt(1 / 0).catchAll(_ => ZIO.fail(())).exit
          } yield assert(exit)(fails(isUnit))
        }
      ),
      suite("TestConsoleSpec")(
        test("say hello to the World") {
          for {
            _     <- TestConsole.feedLines("World")
            _     <- greet
            value <- TestConsole.output
          } yield assertTrue(value == Vector("Hello, World!\n"))
        }
      ),
      suite("TestClockSpec")(
        test("goShopping delays for one hour") {
          for {
            fiber <- goShopping.fork
            _     <- TestClock.adjust(1.hour)
            _     <- fiber.join
          } yield assertCompletes
        }
      )
    )
}
