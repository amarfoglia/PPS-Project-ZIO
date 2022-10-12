package it.unibo.example.transaction

import zio.ZIOAppDefault
import zio.stm.TArray
import zio.stm.USTM
import zio.ZIO

object MyTArray {

  def swap[A](array: TArray[A], i: Int, j: Int): USTM[Unit] =
    for {
      a1 <- array(i)
      a2 <- array(j)
      _  <- array.update(i, _ => a2)
      _  <- array.update(j, _ => a1)
    } yield ()
}

object ArraySwapMain extends ZIOAppDefault {

  import MyTArray.swap

  val program =
    for {
      a1      <- TArray.make(0, 1, 2, 3)
      _       <- swap(a1, 0, 3)
      results <- a1.apply(0) <*> a1.apply(3)
    } yield results

  val run = program.commit.flatMap(ZIO.debug(_)) // (3, 0)
}
