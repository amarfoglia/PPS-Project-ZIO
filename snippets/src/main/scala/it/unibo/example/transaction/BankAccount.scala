package it.unibo.example.transaction

import zio.stm.TRef
import zio.stm.{STM, USTM}
import zio.Task
import zio.Ref
import zio.ZIOAppDefault
import zio.Console
import zio.ZIO

final case class Balance private(private val value: TRef[Int]) { self =>
  def transfer(that: Balance, amount: Int): Task[Int] =
    val transaction: STM[Throwable, Int] =
      for
        senderBalance  <- value.get
        currentBalance <- if (amount > senderBalance)
          STM.fail(Throwable("insufficient funds"))
        else self.value.update(_ - amount) *>
          that.value.update(_ + amount) *>
          self.value.get
      yield currentBalance
    transaction.commit

  def autoDebit(amount: Int): USTM[Int] =
    for
      balance        <- value.get
      currentBalance <- if (balance >= amount)
        self.value.update(_ - amount) *>
          self.value.get
      else STM.retry
    yield currentBalance
}

object Balance {
  def make(initialAmount: Int) =
    TRef.make(initialAmount).map(Balance(_)).commit
}

object BankAccount extends ZIOAppDefault {

  val program = for {
    b1        <- Balance.make(110)
    b2        <- Balance.make(150)
    transfer1  = b1.transfer(b2, 100)
    debit      = b1.autoDebit(100).commit
    transfer2  = b2.transfer(b1, 100)
    results   <- ZIO.collectAllPar(Vector(transfer1, debit, transfer2))
    _         <- Console.printLine(results)  // (10, 10, 50)
  } yield ()

  def run = program
}
