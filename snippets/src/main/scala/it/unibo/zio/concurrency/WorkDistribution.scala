package it.unibo.zio.concurrency

import zio._

object WorkDistribution {

  object BackPressure {

    val program: UIO[Unit] = for {
      queue <- Queue.bounded[String](2)
      _ <- queue
        .offer("ping")
        .tap(_ => Console.printLine("ping"))
        .forever
        .fork
    } yield ()
  }

  object Sliding {

    val program: UIO[(Int, Int)] = for {
      queue <- Queue.sliding[Int](2)
      _     <- ZIO.foreach(List(1, 2, 3))(queue.offer)
      a     <- queue.take
      b     <- queue.take
    } yield (a, b)
  }

  object Dropping {

    val program: UIO[(Int, Int)] = for {
      queue <- Queue.dropping[Int](2)
      _     <- ZIO.foreach(List(1, 2, 3))(queue.offer)
      a     <- queue.take
      b     <- queue.take
    } yield (a, b)
  }
}

//object Main extends ZIOAppDefault {
//  import WorkDistribution._
//
//  def run = Sliding.program flatMap (Console.printLine(_))
//}
