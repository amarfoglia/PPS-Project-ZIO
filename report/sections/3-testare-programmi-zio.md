# ZIO Test

Come già anticipato, ZIO consente di sviluppare programmi complessi in maniera incrementale sfruttando la _componibilità_ degli _effect_. Questo è possibile solo nel caso in cui i singoli componenti rispettino le garanzie sul comportamento previsto. Adottando unicamente _ScalaTest_, durante l'implementazione di codice di verifica, non è possibile avvalersi di tutti i vantaggi derivanti dalle caratteristiche di ZIO. A tal proposito nasce **ZIO Test**, cioè una libreria di test in grado di trattare i test come _effect_. 
```scala
type ZTest[-R, +E] = ZIO[R, TestFailure[E], TestSuccess]
```
Un test non è altro che un flusso di lavoro che può completare con `TestSuccess`, oppure fallire con `TestFailure` parametrizzato su un tipo di errore `E`. Il fallimento può derivare dal mancato soddisfacimento di un asserzione (`TestFailure.Assertion`), o da un errore in fase di esecuzione (`TestFailure.Runtime`). 

Concettualizzare un test come un _effect_:

- evita l'utilizzo di `unsafeRun` ogni volta in cui un test coinvolge degli _effect_;
- permette di unificare codice di test che racchiude _effect_ con quello che ne è privo. Per creare un test che non coinvolge _effect_, è possibile convertire i valori in _effect_ tramite i costruttori `ZIO.succeed`, `ZIO.fail` e `ZIO.succeed`;
- consente di avvalersi dell'intero set di funzionalità di ZIO, al fine di risolvere i problemi durate l'attività di test. Un esempio può essere il meccanismo per la gestione delle risorse. 

ZIO Test internamente, per catturare il risultato di un'asserzione, utilizza il tipo di dato `BoolAlgebra`, il quale permette inoltre di combinare più asserzioni tra loro. Ogni test scritto all'interno del costrutto `test` ritorna uno `ZIO[R, E, TestResult]`, dove `TestResult` è un alias per `BoolAlgebra[FailureDetails]` e `FailureDetails` contiene i dettagli relativi al risultato di una specifica asserzione.

Un banale esempio di test che impiega l'asserzione di ZIO:
```scala
object ExampleSpec extends ZIOSpecDefault:
  def spec = suite("ExampleSpec")(
    test("ZIO.succeed must be equal to 2") {
      assertZIO(ZIO.succeed(1 + 1))(equalTo(2))
    }
  )
```
Siccome un test (nel esempio `spec`) ne può contenere altri innestati su più livelli, il _framework_ dovrà scorrerli tutti prendendosi automaticamente carico degli aspetti legati all'ambiente di esecuzione e garantendo la consistenza dei risultati tra piattaforme. Uno `ZSpec` è quindi una struttura dati ad albero che può contenere uno o più test. Inoltre `assertZIO` altro non è, che una composizione degli operatori `map` e `assert` e può essere riscritto mediante _for comprehension_:
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

## Testing: Assertions
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

## Testing: Test Aspects

Un _test aspect_ può essere rappresentato come una funzione da `spec` a `spec`. Infatti quando si applica un _aspect_ a un test, tramite l'operatore `@@`, si ottiene in uscita un nuovo test. Lo scopo è quello di separare la logica del "cosa" si vuole testare da quella del "come". Questo permetterebbe di rendere il codice più leggibile favorendone la modularità.

Si prenda come esempio un test che verifica il comportamento dell'operatore `foreachPar`, nello specifico l'ordine dei risultati deve essere preservato. 
```scala
test("foreachPar preserves ordering") {
  val zio = ZIO.foreach(1 to 100) { _ =>
    ZIO.foreachPar(1 to 100)(ZIO.succeed(_)).map(_ == (1 to 100))
  }.map(_.forall(identity))
  assertZIO(zio)(isTrue)
}
```
Siccome il corpo del test viene eseguito in maniera concorrente, è necessario avviare più volte il test al fine di ridurre il non determinismo. Questa strategia però non è ideale,perché fonde aspetti relativi al "cosa" si vuole testare con quelli del "come". 

Facendo sempre riferimento all'esempio, il "cosa" si vuole verificare è descritto dall'_effect_ interno, mentre il "cosa" è determinato dal `foreach` più esterno. La scissione tra i due può essere effettuata tramite _test aspects_, in particolare avvalendosi dell'operatore `nonFlaky` che consente di eseguire il test tante volte accertando la positività di tutti i risultati.
```scala
test("foreachPar preserves ordering") {
  for {
    values <- ZIO.foreachPar(1 to 100)(ZIO.succeed(_))
  } yield assert(values)(equalTo(1 to 100))
} @@ nonFlaky(100)
```

Esistono altri operatori oltre a `nonFlaky` che, essendo componibili, permettono di cambiare agilmente la configurazione del test in base alle esigenze, evitando di modificare il corpo del test.
```scala
test("foreachPar preserves ordering") {
  assertZIO(ZIO.foreachPar(1 to 100)(ZIO.succeed(_)))
    (equalTo(1 to 100))
} @@ nonFlaky(100) @@ jvmOnly @@ timeout(60.seconds)
```

## Testing: ZIO services

ZIO Test permette di migliorare la _coverage_ del codice fornendo automaticamente, una copia distinta dei _services_ (`TestConsole`, `TestClock`, `TestRandom` e `TestSystem`), sotto forma di _environment_, ad ogni test. Ad esempio, nel caso in cui si voglia testare un programma che richiede l'inserimento di una parola da parte dell'utente, sorgono due problemi fondamentali: i valori da verificare devono essere definiti manualmente, con conseguente riduzione del numero dei possibili scenari, e la _continuous integration_ non è supportata. 

### Testing - Console service

Tramite l'API di `TestConsole`, i valori di input e di output della _console_ vengono concettualizzati come due _buffer_.
```scala 
trait TestConsole extends Restorable {
  def clearInput: UIO[Unit]
  def clearOutput: UIO[Unit]
  def feedLines(lines: String*): UIO[Unit]
  def output: UIO[Vector[String]]
  def outputErr: UIO[Vector[String]]
}
```
Il metodo `readLine` legge il prossimo valore dal primo _buffer_, mentre `printLine` scrive sul _buffer_ di uscita il valore passatogli. Inoltre l'operatore `feedLines` consente di inserire nel _buffer_ di ingresso le linee da cui `readLine` attingerà, mentre `output` permette di recuperare le informazioni prodotte da `printLine`.

Sfruttando questa API si è grado di realizzare del codice interamente testabile che, se opportunamente integrato con il supporto per il _property base testing_, consente di raggiungere elevati livelli di _coverage_.
```scala
test("say hello to the World") {
  for {
    _     <- TestConsole.feedLines("World")
    _     <- greet
    value <- TestConsole.output
  } yield assert(value)(equalTo(Vector("Hello, World!\n")))
}
```

### Testing - Clock service

Un altro servizio di test particolarmente utile per la verifica di programmi concorrenti è `TestClock`. Questo consente di testare in maniera deterministica gli _effect_ che dipendono dal tempo senza però aspettare che passi il tempo reale. Internamente utilizza una coda di _task_ pendenti, non vi è una vera programmazione (_schedule_) degli _effect_ da eseguire.

Quando si lavoro il servizio `TestClock`, generalmente la definizione di un test implica i seguenti passaggi:

1. esecuzione concorrente (`fork`) dell'_effect_ che dipende dal passaggio del tempo;
2. regolazione (`adjust`) del `TestClock` al tempo richiesto dal _effect_ per completare;
3. comparazione dei risultati dell'_effect_ con quelli aspettati.

Un esempio che concretizza i passaggi appena elencati:
```scala
test("test effects involving time") {
  for {
    ref   <- Ref.make(false)
    _     <- ref.set(true).delay(1.hour).fork
    _     <- TestClock.adjust(1.hour)
    value <- ref.get
  } yield assert(value)(isTrue)
}
```
Il test viene superato deterministicamente senza ritardi, grazie al metodo `adjust`. Questo esegue immediatamente, ed ordinatamente, tutti gli _effects_ la cui esecuzione è programmata/posticipata; nell'esempio provoca l'aggiornamento istantaneo del valore di `ref` a `true`.

`TestClock` è di supporto anche in contesti più complessi, come nel caso degli `Stream`. Si prenda come ultimo esempio la verifica del comportamento dell'operatore `zipWithLatest`. Questo consente di combinare due `Stream` in uno nuovo, i cui valori d i uscita sono quelli dei primi due presi in coppia. Di seguito viene proposta una possibile implementazione del test.
```scala
test("testing a stream operator involving the passage of time") {
  val s1 = Stream.iterate(0)(_ + 1).fixed(100.milliseconds)
  val s2 = Stream.iterate(0)(_ + 1).fixed(70.milliseconds)
  val s3 = s1.zipWithLatest(s2)((_, _))
  for {
    q      <- Queue.unbounded[(Int, Int)]
    _      <- s3.foreach(q.offer).fork
    fiber  <- ZIO.collectAll(ZIO.replicate(4)(q.take)).fork
    _      <- TestClock.adjust(1.second)
    result <- fiber.join
  } yield assert(result)(
      equalTo(List(0 -> 0, 0 -> 1, 1 -> 1, 1 -> 2))
    )
}
```

### Testing - Random service

Il servizio `TestRandom` fornisce una implementazione testabile di `Random`, e può lavorare in due modalità. La prima è quella in cui la logica di generazione del numero si basa su un _seed_ passato in input. La seconda modalità consente al programmatore di passare un set di valori predefiniti che andrà ad alimentare il generatore.

Un esempio applicativo della prima modalità:
```scala
for {
  _      <- TestRandom.setSeed(42L)
  first  <- Random.nextLong
  _      <- TestRandom.setSeed(42L)
  second <- Random.nextLong
} yield assert(first)(equalTo(second))
```
Siccome è stato utilizzato lo stesso _seed_, i numeri generati sono equivalenti, quindi il test completerà sempre con successo.

Infine viene proposto un esempio che sfrutta la seconda modalità:
```scala
for {
  _ <- TestRandom.feedInts(1, 2, 3)
  x <- Random.nextInt
  y <- Random.nextInt
  z <- Random.nextInt
} yield assert((x, y, z))(equalTo((1, 2, 3)))
```
In questo caso, `TestRandom` restituisce in ordine i valori con cui è stato alimentato.


## Testing: Property based testing

ZIO Test fornisce un supporto per i test basati sulle proprietà; l'idea è quella di generare un'intera raccolta di input partendo da una distribuzione parziale definita dal programmatore. Questa tecnica consente di ampliare lo spettro dei possibili scenari di verifica massimizzando la produttività e la rilevazione di eventuali bug. Nella pratica ZIO abilita tale strumento tramite il tipo di dato `Gen[R, A]`, che rappresenta un generatore di valori di tipo `A` con un _environment_ `R`, e la famiglia di operatori `check`.

In ZIO Test, un generatore corrisponde a uno _stream_ di campioni:
```scala
import zio.stream._
final case class Gen[-R, +A](
  sample: ZStream[R, Nothing, Sample[R, A]]
)
```

Un esempio con cui concretizzare i concetti appena presentati, può essere un test che permette di verificare la proprietà associativa della somma tramite il supporto di un generatore di numeri interi:
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
Nel caso di tipi di dati _custom_, è comunque possibile realizzare dei generatori ad-hoc componendo quelli di base offerti da `Gen`.

Questa soluzione di test però potrebbe non essere sufficientemente esaustiva nell'identificare controesempi specifici. Inoltre la bontà dei test basati sulle proprietà dipende strettamente dai campioni definiti dal programmatore. Un valido generatore deve produrre risultati abbastanza specifici per verificare le proprietà di interesse, ma deve anche essere sufficientemente generale per coprire l'intera gamma di valori.

Detto ciò, la costruzione di un _property based test_ si struttura su tre parti:

- un operatore `check` che gestisce i parametri di esecuzione del test;
- uno o più valori di tipo `Gen` con i quali passare le distribuzioni di valori;
- un'`assertion` che riceve in input i valori generati.

Per quanto riguarda gli operatori, anche per i generatori è possibile sfruttare la gran parte di quelli già citati. Utilizzando `map` i valori generati vengono trasformati attraverso la funzione passata; per motivi di efficienza è preferibile trasformare i generatori piuttosto che filtrarli. 

Nel caso di un generatore di numeri interi nell'intervallo da `1` a `100`, è possibile trasformarlo in uno di soli numeri pari sfruttando l'operatore `map`.
```scala
val ints: Gen[Random, Int] =
  Gen.int(1, 100)

val evens: Gen[Random, Int] =
  ints.map(n => if (n % 2 == 0) n else n + 1)
```

Infine i generatori possono essere combinati tramite appositi operatori come `flatMap`, `cross` e `crossWith` (alias simbolico `<*>`), `forEach`, `collectAll` e `zip` con le relative varianti. Di seguito viene presentato un esempio di combinazione tra due generatori di interi.
```scala
val pairs: Gen[Random, (Int, Int)] =
  Gen.int <*> Gen.int
```
Quando i generatori non sono dipendenti tra loro, è preferibile adottare una sintassi che si avvale della `for comprehension`.
```scala
val pairs2 = for {
  x <- Gen.int
  y <- Gen.int
} yield (x, y)
```

