package it.unibo.zio.concurrency

import zio.*

object Interruption {

  val defaultInterruption = for {
    promise <- Promise.make[Nothing, Unit]
    effect = promise.succeed(()) *> ZIO.never
    finalizer = ZIO
      .succeed(println("Closing file"))
      .delay(2.seconds)
    fiber <- effect.ensuring(finalizer).fork
    _     <- promise.await
    _     <- fiber.interrupt
    _     <- ZIO.succeed(println("Done interrupting"))
  } yield ()

  val asyncInterruption = for {
    promise <- Promise.make[Nothing, Unit]
    effect = promise.succeed(()) *> ZIO.never
    finalizer = ZIO
      .succeed(println("Closing file"))
      .delay(5.seconds)
    fiber <- effect.ensuring(finalizer).fork
    _     <- promise.await
    _     <- fiber.interrupt.fork
    _     <- ZIO.succeed(println("Done interrupting"))
  } yield ()

  object DisconnectAndInterrupt {

    private val a: URIO[Clock, Unit] =
      ZIO.never
        .ensuring(
          ZIO
            .succeed("Closed A")
            .delay(3.seconds)
        )

    private val b =
      ZIO.never
        .ensuring(
          ZIO
            .succeed("Closed B")
            .delay(5.seconds)
        )
        .disconnect

    val program = for {
      fiber <- (a <&> b).fork
      _     <- Clock.sleep(1.second)
      _     <- fiber.interrupt
      _     <- ZIO.succeed(println(s"Done interrupting"))
    } yield ()
  }

}

//object Main extends ZIOAppDefault {
//  import Interruption._
//
//  def run =
//    DisconnectAndInterrupt.program
//      .provideEnvironment(ZEnvironment(Clock.ClockLive))
//}
