package it.unibo.zio.concurrency

import zio.*

object WorkLimiting {

  trait RefM[A] {
    def modify[R, E, B](f: A => ZIO[R, E, (B, A)]): ZIO[R, E, B]
  }

  object RefM {

    def make[A](a: A): UIO[RefM[A]] =
      for {
        ref       <- Ref.make(a)
        semaphore <- Semaphore.make(1)
      } yield new RefM[A] {

        def modify[R, E, B](
          f: A => ZIO[R, E, (B, A)]
        ): ZIO[R, E, B] =
          semaphore.withPermit {
            for {
              a <- ref.get
              v <- f(a)
              _ <- ref.set(v._2)
            } yield v._1
          }
      }
  }
}

object Main extends ZIOAppDefault {
  import WorkLimiting._

  def run =
    for {
      ref <- RefM.make(0)
      zioModify = ref.modify(v => ZIO.succeed(v, v + 1));
      _ <- zioModify.fork
      _ <- zioModify.fork
      _ <- zioModify
    } yield ()
}
