package it.unibo.example

import scala.util.Random as ScalaRandom

import zio._

object SafeDelayedExecution extends ZIOAppDefault {

  val printRandomNumber: ZIO[Any, Throwable, Unit] =
    ZIO.attempt(println(s"Generated number: ${ScalaRandom.nextInt()}"))

  val printRandomNumberLater: ZIO[Any, Throwable, Unit] =
    printRandomNumber.delay(1.seconds)

  val run: Task[Unit] = printRandomNumberLater
}
