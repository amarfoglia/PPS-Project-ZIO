# ZIO Test

Come già anticipato, ZIO consente di sviluppare programmi complessi in maniera incrementale sfrattando la _componibilità_ degli _effect_. Questo è possibile solo nel caso in cui i singoli componenti rispettano le garanzie sul comportamento previsto. Adottando unicamente _ScalaTest_, durante l'implementazione di codice di verifica, non è possibile avvalersi di tutti i vantaggi derivanti dalle caratteristiche di ZIO. A tal proposito nasce **ZIO Test**, cioè una libreria di test che permette di manipolare gli _effect_ come _valori di prima classe_.

Un banale esempio di test tramite asserzione di ZIO:
```scala
object ExampleSpec extends ZIOSpecDefault:
  def spec = suite("ExampleSpec")(
    test("ZIO.succeed must be equal to 2") {
      assertZIO(ZIO.succeed(1 + 1))(equalTo(2))
    }
  )
```
Siccome un test (nel esempio `spec`) ne può contenere altri innestati su più livelli, il _framework_ dovrà scorrerli tutti prendendosi automaticamente carico degli aspetti legati all'ambiente di esecuzione e garantendo la consistenza dei risultati tra piattaforme. Inoltre `assertZIO` altro non è, che una composizione degli operatori `map` e `assert` e può essere riscritto mediante _for comprehension_:
```scala
object ExampleSpec extends ZIOSpecDefault {
  def spec = suite("ExampleSpec")(
    test("testing an effect using map operator") {
      ZIO.succeed(1 + 1).map(n => assert(n)(equalTo(2)))
    },
    test("testing an effect using a for comprehension") {
      for {
        n <- ZIO.succeed(1 + 1)
      } yield assert(n)(equalTo(2))
    }
  )
}
```
Le asserzioni possono essere combinate tra loro usando gli operatori di congiunzione (`&&`) e disgiunzione (`||`) logica. Inoltre è possibile negare un'asserzione tramite `!`.

## Asserzioni
Nell'esempio precedente, è stata utilizza l'asserzione `equalTo`, ma ne esistono altre all'interno del _package_ `zio.test`. Un modo semplice per comprendere il significato di `Assertion[A]` è rappresentarla come una funzione che riceve in ingresso un valore di tipo `A` e restituisce un `Boolean` che ne determina il soddisfacimento. 
```scala
type Assertion[-A] = A => Boolean

def equalTo[A](expected: A): Assertion[A] =
  actual => actual == expected
```
Un'altra caratteristica rilevante è che molto asserzione possono ricevere in ingresso altre asserzioni come argomenti. Questo permette di esprimere asserzioni più specifiche, come nel caso di `fail` il cui parametro in input consente di determinare il valore di fallimento dell'_effect_.
```scala
object ExampleSpec extends ZIOSpecDefault {
  def spec = suite("ExampleSpec")(
    test("fails") {
      for {
        exit <- ZIO.attempt(1 / 0).catchAll(_ => ZIO.fail(())).exit
      } yield assert(exit)(fails(isUnit)) // isUnit = equalTo(())
    }
  )
}
```

## Testare gli _ZIO services_
ZIO Test permette di migliorare la _coverage_ del codice fornendo automaticamente una copia distinta dei _services_ (`TestConsole`, `TestClock`, `TestRandom` e `TestSystem`), sotto forma di _environment_, ad ogni test. Ad esempio, nel caso in cui si voglia testare un programma che richiede l'inserimento di una parola da parte dell'utente, sorgono due problemi fondamentali: i valori da verificare devono essere definiti manualmente, con conseguente riduzione del numero dei possibili scenari, e non la _continuous integration_ non è supportata. 

Tramite l'API di `TestConsole`, i valori di input e di output vengono forniti sotto forma di _buffer_. In questo modo si ottiene un codice interamente testabile che, se opportunamente integrato con il supporto per il _property base testing_, consente di raggiungere elevati livelli di _coverage_.
```scala
object ExampleSpec extends ZIOSpecDefault {
  def spec = suite("ExampleSpec")(
    test("say hello to the World") {
      for {
        _ <- TestConsole.feedLines("World")
        _ <- greet
        value <- TestConsole.output
      } yield assert(value)(equalTo(Vector("Hello, World!\n")))
    }
  )
}
```

Un altro servizio di test particolarmente utile per la verifica di programmi concorrenti è `TestClock`. Questo consente di testare in maniera deterministica gli _effect_ che dipendono dal tempo senza però aspettare che passi il tempo reale. Di seguito viene presentato un esempio di test:
```scala
val goShopping: ZIO[Any, Nothing, Unit] =
  Console.printLine("Going shopping!").orDie.delay(1.hour)

object ExampleSpec extends ZIOSpecDefault {
  def spec = suite("ExampleSpec")(
    test("goShopping delays for one hour") {
      for {
        fiber <- goShopping.fork // run in a separate process
        _ <- TestClock.adjust(1.hour)
        _ <- fiber.join
      } yield assertCompletes
    }
  )
}
```
Il metodo `adjust` esegue immediatamente ed in ordine tutti gli _effects_ la cui esecuzione sarebbe programmata/posticipata, nell'esempio provoca il completamento e quindi la terminazione del programma `goShopping`.

## Property based testing

ZIO Test fornisce un supporto per i test basati sulle proprietà, la cui idea è quella generare un'intera raccolta di input a partire da una distribuzione di potenziali input definiti dal programmatore. Questa tecnica consente di ampliare lo spettro dei possibili scenari di verifica massimizzando la produttività e la rilevazione di eventuali bug. Nella pratica ZIO abilita il test basato sulle proprietà, grazie al tipo di dato `Gen[R, A]`, che rappresenta un generatore di valori di tipo `A` con un _environment_ `R`, e alla famiglia di operatori `check`.

Per esempio, è possibile verificare che la somma sia associativa tramite il supporto di un generatore di numeri interi:
```scala
object PropertyBaseTest extends ZIOSpecDefault {
  val intGen: Gen[Any, Int] = Gen.int
  def spec = suite("ExampleSpec")(
    test("integer addition is associative") {
      check(intGen, intGen, intGen) { (x, y, z) =>
        val left  = (x + y) + z
        val right = x + (y + z)
        assertTrue(left == right)
      }
    }
  )
}
```
Nel caso di tipi di dato _custom_, è comunque possibile realizzare dei generatori ad-hoc componendo quelli di base offerti da `Gen`.

