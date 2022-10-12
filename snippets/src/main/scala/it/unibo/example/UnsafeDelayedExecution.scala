package it.unibo.example

object UnsafeDelayedExecution extends App {
  import scala.util.Random
  import java.util.concurrent.{Executors, ScheduledExecutorService}
  import java.util.concurrent.TimeUnit._

  val printRandomNumber: Unit = {
    println(s"Generated number: ${Random.nextInt()}")
  }

  val scheduler: ScheduledExecutorService =
    Executors.newScheduledThreadPool(1)

  scheduler.schedule(
    new Runnable { def run(): Unit = printRandomNumber },
    1,
    SECONDS
  )
  scheduler.shutdown()
}
