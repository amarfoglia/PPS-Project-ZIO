package it.unibo.example.transaction

import zio.IO
import zio.stm.STM
import zio.stm.TSemaphore

object TSemaphoreExample {
  def dummySTMAction: STM[Nothing, Unit] = STM.unit

  val tSemaphoreWithPermit: IO[Nothing, Unit] =
    for {
      sem    <- TSemaphore.make(1L).commit
      result <- sem.withPermit(dummySTMAction.commit)
    } yield result
}
