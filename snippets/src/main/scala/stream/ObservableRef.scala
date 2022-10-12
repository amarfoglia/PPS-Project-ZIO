package stream

import zio.Chunk
import zio.Console
import zio.Random
import zio.Ref
import zio.Schedule
import zio.UIO
import zio.ZIO
import zio.ZIOAppDefault
import zio.durationInt
import zio.stream.SubscriptionRef
import zio.stream.UStream
import zio.stream.ZStream

object ProducerConsumer {
  def server(ref: Ref[Long]): UIO[Nothing] = ref.update(_ + 1).forever

  def client(changes: UStream[Long]): UIO[Chunk[Long]] =
    for {
      n     <- Random.nextLongBetween(1, 200)
      chunk <- changes.take(n).runCollect
    } yield chunk
}

import ProducerConsumer._

object ObservableRef extends ZIOAppDefault {

  val program =
    for {
      ref    <- SubscriptionRef.make(0L)
      server <- server(ref).fork
      chunk  <- client(ref.changes)
      _      <- server.interrupt
    } yield chunk

  val run = program.flatMap(ZIO.debug(_))
}
