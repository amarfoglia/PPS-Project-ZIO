package it.unibo.example.myzio

object MyZIO {

  final case class ZIO[-R, +E, +A](run: R => Either[E, A]) { self =>
    def map[B](f: A => B): ZIO[R, E, B] = ZIO(r => self.run(r).map(f))

    def flatMap[R1 <: R, E1 >: E, B](f: A => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
      ZIO(r => self.run(r).fold(ZIO.fail(_), f).run(r))

    def provide(r: R): ZIO[Any, E, A] = ZIO(_ => self.run(r))
  }

  object ZIO {

    def attempt[A](a: => A): ZIO[Any, Throwable, A] =
      ZIO(_ =>
        try Right(a)
        catch { case t: Throwable => Left(t) }
      )

    def fail[E](e: => E): ZIO[Any, E, Nothing] = ZIO(_ => Left(e))

    def environment[R]: ZIO[R, Nothing, R] = ZIO(r => Right(r))
  }
}
