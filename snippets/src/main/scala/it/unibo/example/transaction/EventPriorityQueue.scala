package it.unibo.example.transaction

import zio.Clock
import zio.UIO
import zio.ZIO
import zio.ZIOAppDefault
import zio.stm.TPriorityQueue

import java.util.concurrent.TimeUnit

final case class Event(time: Long, name: String, action: UIO[Unit])

object Event {

  implicit val EventOrdering: Ordering[Event] =
    Ordering.by(_.time)

  def dummy(name: String) =
    Clock
      .currentTime(TimeUnit.MILLISECONDS)
      .map(Event(_, name, ZIO.unit))
}

object EventPriorityQueue extends ZIOAppDefault {

  val program = for {
    queue  <- TPriorityQueue.empty[Event].commit
    e1     <- Event.dummy("Event 1")
    e2     <- Event.dummy("Event 2")
    _      <- queue.offer(e2).commit
    _      <- queue.offer(e1).commit
    result <- queue.takeAll.commit // it always takes (e1, e2)
  } yield result

  val run = program.flatMap(ZIO.debug(_))
}
