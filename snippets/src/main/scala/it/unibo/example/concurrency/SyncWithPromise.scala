package it.unibo.zio.concurrency

import zio.{Console, Promise, ZIOAppDefault}

object SyncWithPromise {

  val program1 = for {
    promise <- Promise.make[Nothing, Unit]
    left    <- (Console.print("Hello, ") *> promise.succeed(())).fork
    right   <- (promise.await *> Console.print("World!")).fork
    _       <- left.join *> right.join
  } yield ()
}
