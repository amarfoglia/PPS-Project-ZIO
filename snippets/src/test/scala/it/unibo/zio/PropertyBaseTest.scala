package it.unibo.zio

import zio.test.*
import zio.test.Assertion.equalTo

object PropertyBaseTest extends ZIOSpecDefault {
  val intGen: Gen[Any, Int] = Gen.int

  def spec =
    suite("ExampleSpec")(
      test("integer addition is associative") {
        check(intGen, intGen, intGen) { (x, y, z) =>
          val left  = (x + y) + z
          val right = x + (y + z)
          assertTrue(left == right)
        }
      }
    )
}
