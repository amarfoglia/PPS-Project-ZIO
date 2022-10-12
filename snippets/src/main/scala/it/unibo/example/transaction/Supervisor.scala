package it.unibo.example.transaction

import zio.UIO
import zio.ZIO
import zio.stm.TReentrantLock

type Actor

trait Supervisor {
  def lock: TReentrantLock
  def supervise(actor: Actor): UIO[Unit]
  def unsupervise(actor: Actor): UIO[Unit]
}

object Supervisor {

  def transfer(from: Supervisor, to: Supervisor, actor: Actor): UIO[Unit] = {
    ZIO.acquireReleaseWith {
      (from.lock.acquireWrite *> to.lock.acquireWrite).commit
    } { _ =>
      (from.lock.releaseWrite *> to.lock.releaseWrite).commit
    } { _ =>
      from.unsupervise(actor) *> to.supervise(actor)
    }
  }
}
