# Elementi fondamentali

Il tipo di dato _core_ della libreria `ZIO` è chiamato _functional effect_ e viene formalizzato come `ZIO[R, E, A]`. In sequenza, `R` (_environment type_) rappresenta l'_environment_ e viene fornito dal chiamante, `E` (_error type_) è il tipo di errore generato in caso di fallimento, mentre `A` (_success type_) corrisponde al tipo del valore prodotto in caso di successo. Prima di procedere ad un loro approfondimento è necessario chiarire il significato di _effect_.

Molti dei linguaggi di programmazione sono procedurali, e rappresentano una soluzione conveniente nel caso di programmi semplici, ma non permettono di scindere il "cosa" (_what_) il programma dovrà fare, dal "come" (_how_). Questo intreccio può portare ad un'elevata mole di codice _boilerplate_ che, oltre ad essere prono a bug, risulta difficilmente testabile e modificabile. Un esempio a sostegno di quanto affermato può essere l'assegnazione di un _task_ allo `ScheduledExecutorService` di Java.
```scala
import scala.util.Random
import java.util.concurrent.{ Executors, ScheduledExecutorService }
import java.util.concurrent.TimeUnit._

val printRandomNumber: Unit = {
  println(s"Number: ${Random.nextInt()}")
}

val scheduler: ScheduledExecutorService =
  Executors.newScheduledThreadPool(1)

scheduler.schedule(
  new Runnable { def run(): Unit = printRandomNumber },
  1,
  SECONDS
)
scheduler.shutdown()
```
La logica alla base del codice presentato, può essere riassunta come la visualizzazione, tramite _console_, di un numero generato casualmente. Il tutto avverrà all'interno dello `scheduler` con un ritardo di un secondo. L'implementazione fornita, oltre a richiedere un certo quantitativo di codice non appartenente alla logica di business, nasconde un bug. Poiché `printRandomNumber` viene direttamente eseguito al momento della sua valutazione, la stampa del numero avverrà immediatamente e non dopo un secondo.

`ZIO` pone rimedio a questo problema rendendo le istruzioni del programma una descrizione del programma stesso. In questo modo è possibile separare il "cosa" dal "come". Di seguito viene riproposto lo stesso esempio implementato tramite ZIO.
```scala
import zio._

val printRandomNumber = ZIO.attempt(
  println(s"Number: ${ScalaRandom.nextInt()}")
)

val printRandomNumberLater =
  printRandomNumber.delay(1.seconds)
```
Il metodo `attempt` permette di convertire `printRandomNumber` in un _functional effect_, in altre parole si sta fornendo una descrizione di ciò che la funzione è in grado di fare, senza però eseguirla/valutarla. Il primo vantaggio è la semplificazione del codice, visibilmente meno prolisso rispetto al caso precedente. Il secondo beneficio è la componibilità: ogni `ZIO` _effect_ può essere convertito in un altro _effect_, come nel caso di `delay`, agevolando il programmatore nella costruzione di programmi sempre più complessi.

Dopo aver definito la logica del programma, è possibile eseguire lo `ZIO` _effect_ estendendo l'interfaccia `ZIOAppDefault` ed implementando il metodo `run`. 

```scala
import zio._

object MyApp extends ZIOAppDefault {
  val run = printRandomNumber
}
```

## Composizione sequenziale

Una caratteristica distintiva di `ZIO` sono gli operatori. Questi permettono di trasformare e combinare _effect_ tra loro, al fine di risolvere i problemi comuni durante lo sviluppo delle moderne applicazioni. Uno degli operatori più importanti è la `flatMap` che rappresenta la composizione sequenziale di due _effect_: l'output del primo _effect_ diventa l'input del secondo. L'operatore può essere sfruttato per definire un semplice _workflow_ in cui l'input dell'utente catturato viene visualizzato su _console_.
```scala
import scala.io.StdIn

val readLine =
  ZIO.attempt(StdIn.readLine())

def printLine(line: String) =
  ZIO.attempt(println(line))

val echo =
  readLine.flatMap(line => printLine(line))
```
L'operatore `flatMap` è fondamentale perché cattura il modo in cui le istruzioni vengono eseguite in un programma procedurale. Questa relazione consente di tradurre un qualsiasi programma procedurale in uno `ZIO` _effect_, avvolgendo ogni istruzione all'interno di un costruttore come `ZIO.attempt` e concatenando questi attraverso la `flatMap`. Infine grazie alla ***for comprehension*** di `Scala`, è possibile raggiungere lo stesso potere espressivo della programmazione procedurale. Il programma precedente può essere riscritto come segue:
```scala
import zio._

val echo = for {
  line <- readLine
  _    <- printLine(line)
} yield ()
```

### Operatori sequenziali

Oltre alla `flatMap` è bene ricordare anche la `zipWith` che permette di combinare i risultati di due _effect_ tramite una funzione fornita dall'utente. Di questa esistono diverse varianti:

- `zip`: combina i risultati di due _effect_ in una tupla;
- `zipLeft`: come `zip`, ma ritorna solo il risultato del primo;
- `zipRight`: come `zip`, ma ritorna solo il risultato del secondo.

```scala
val zippedWith: Task[Unit] = Random.nextIntBounded(10)
  .zipWith(Random.nextIntBounded(10)) { (a, b) =>
      println(s"$a + $b = ${a + b}")
  }

val zipped: ZIO[Any, Nothing, (String, Int)] =
  ZIO.succeed("1") zip ZIO.succeed(4)

val zippedRight: ZIO[Any, IOException, String] =
  Console.printLine("What is your name?") *> Console.readLine

val zippedLeft: ZIO[Any, IOException, Unit] =
  Console.printLine("What is your name?") <* Console.readLine
```

Altri operatori degni di nota:

- `foreach`: ritorna un _effect_ che descrive l'esecuzione di una sequenza di _effect_;
- `collectAll`: restituisce un _effect_ che raccoglie i risultati di un'intera raccolta di _effect_.

```scala
val printNumbersByForeach = ZIO.foreach(1 to 10) {
  n => Console.printLine(n.toString)
}

val printNumberByCollectAll = ZIO.collectAll {
  (1 to 10).map(n => Console.printLine(n))
}
```

## ZIO Type Parameters

Come già anticipato nelle sezioni precedenti, `ZIO[R, E, A]` è un _function effect_ che richiede un _environment_ `R` e può fallire con un errore di tipo `E`, oppure completare correttamente con un valore di ritorno di tipo `A`. Di seguito viene proposta una modellazione semplificata dello `ZIO` _effect_.
```scala
final case class ZIO[-R, +E, +A](run: R => Either[E, A])
```
Al fine di migliorare l'inferenza dei tipi, `R` essendo un valore di input è dichiarato _controvariante_, mentre `E` ed `A`, essendo i tipi di ritorno, sono definiti come _covarianti_. Inoltre partendo dalla classe appena proposta, è possibile sviluppare alcuni costruttori e operatori di base:
```scala
final case class ZIO[-R, +E, +A](run: R => Either[E, A]) { self =>
  def map[B](f: A => B): ZIO[R, E, B] =
    ZIO(r => self.run(r).map(f))

  def flatMap[R1 <: R, E1 >: E, B](
    f: A => ZIO[R1, E1, B]
  ): ZIO[R1, E1, B] =
    ZIO(r => self.run(r).fold(ZIO.fail(_), f).run(r))
}

object ZIO {
  def attempt[A](a: => A): ZIO[Any, Throwable, A] =
    ZIO(_ => try Right(a) catch { case t : Throwable => Left(t) })

  def fail[E](e: => E): ZIO[Any, E, Nothing] =
    ZIO(_ => Left(e))
}
```
L'implementazione della `flatMap` fornita esegue l'_effect_ originale passandogli l'_environment_ `R`. In caso di fallimento il valore restituito sarà `Left(e)`, dove `e` è l'errore. In caso di successo, il risultato sarà `Right(a)`, e il valore `a` sarà passato alla funzione `f` con l'intento di generare un nuovo _effect_.

### Error Type

Il tipo dell'errore consente di sfruttare gli operatori, come `flatMap`, considerando il "percorso di successo" e posticipando la gestione degli errori ai livelli superiori. Prendendo come esempio il codice seguente:
```scala
import zio._

lazy val readInt: ZIO[Any, NumberFormatException, Int] =
  ???

lazy val readAndSumTwoInts: ZIO[Any, NumberFormatException, Int] =
  for {
    x <- readInt
    y <- readInt
  } yield x * y
```
In questo caso, nonostante l'implementazione di `readInt` sia sconosciuta, adottando ZIO è possibile intuire direttamente dalla firma che: l'istruzione non richiede nessun _environment_, il tipo di ritorno è un numero intero e può presentarsi un fallimento solo con un `NumberFormatException`. Conoscere a priori la tipologia degli errori da gestire permette di evitare la _programmazione difensiva_[^4]. Infine specificando il tipo `E` come `Nothing` si è in grado di esprimere l'impossibilità di un _effect_ di fallire.

[^4]: Per approfondimenti: [https://wiki/defensive_programming](https://en.wikipedia.org/wiki/Defensive_programming)

### Environment Type

`ZIO` utilizza il tipo parametrico `R` per definire l'_environment_ necessario all'_effect_ per eseguire. In altre parole, consente di ragionare sulle dipendenze in maniera statica. Queste diventano semplici interfacce allo scopo di rendere il codice interamente testabile e modulare. Inoltre l'_environment_ permette di definire le dipendenze evitando la loro propagazione lungo l'applicazione (_dependency injection_). Specificando `R` come `Any` si modella un _effect_ che non richiede alcun _environment_. 

L'interazione con l'_environment_, avviene tramite i metodi `environment` e `provide`; il primo permette di accedervi, mentre il secondo si occupa della sua fornitura. Per chiarire meglio i concetti è possibile estendere la versione "giocattolo" di `ZIO`, con le implementazioni delle operazioni appena citate.
```scala
final case class ZIO[-R, +E, +A](run: R => Either[E, A]) { self =>
  def provide(r: R): ZIO[Any, E, A] =
    ZIO(_ => self.run(r))
}

object ZIO {
  def environment[R]: ZIO[R, Nothing, R] =
    ZIO(r => Right(r))
}
```

### `ZIO` Type Aliases

In alcuni casi non è necessario sfruttare l'intero potere espressivo derivante dai tre tipi parametrici, per questo motivo `ZIO` fornisce degli alias, e relativi _companion object_, con l'intento di favorire la leggibilità del codice.
```scala
type IO[+E, +A]   = ZIO[Any, E, A]
type Task[+A]     = ZIO[Any, Throwable, A]
type RIO[-R, +A]  = ZIO[R, Throwable, A]
type UIO[+A]      = ZIO[Any, Nothing, A]
type URIO[-R, +A] = ZIO[R, Nothing, A]
```

## Effect constructor

Prima di introdurre gli _effect constructor_ di `ZIO` è necessario approfondire il concetto di ***trasparenza referenziale***[^5]. Un'espressione è referenzialmente trasparente, se può essere sostituita dal suo valore senza modificare il comportamento del programma. Prendendo in considerazione il codice seguente:
```scala
import scala.io.StdIn

val echo: Unit = {
  val line = StdIn.readLine()
  println(line)
}
```
Non è possibile sostituire il corpo di `echo` con il suo valore perché `val echo: Unit = ()` rappresenta un programma differente. Questo è dovuto alla presenza di _side effects_. I costruttori di ZIO, come `attempt`, permettono di ricondurre il codice soprastante a _funzioni pure_[^6], catturando i _side effects_, cioè tracciando la loro descrizione, senza però eseguirli. Lo stesso programma rivisitato tramite ZIO:
```scala
val readLine: Task[String] =
    ZIO.attempt(StdIn.readLine())

def printLine(line: String): Task[Unit] =
  ZIO.attempt(println(line))

val echoZIO: Task[Unit] = for {
  line <- readLine
  _ <- printLine(line)
} yield ()
```
[^5]:  Per ulteriori dettagli: [link a Wikipedia](https://en.wikipedia.org/wiki/Referential_transparency)
[^6]: funzioni che soddisfano la proprietà di _trasparenza referenziale_

### Computazioni pure

`ZIO` fornisce una varietà di costruttori per convertire _valori puri_ in `ZIO` _effect_. Questi trovano impiego quando è necessario combinare _codice puro_ con degli _effect_ che racchiudono _side effects_. I due costruttori più importanti di questa categoria sono `succeed` e `fail`:
```scala
object ZIO {
  def fail[E](e: => E): ZIO[Any, E, Nothing] = ???
  def succeed[A](a: => A): ZIO[Any, Nothing, A] = ???
}
```
La funzione `succeed`, preso un certo valore `a`, restituisce un _effect_ il cui valore, in caso di "successo", è `a` e il tipo di errore è `Nothing`. La funzione `fail` è l'inversa della precedente. I parametri di entrambi i costruttori sono valutati in maniera _lazy_.

### Computazioni con side effects

Ovviamente i costruttori più importanti sono quelli per la gestione delle computazioni affette da _side effects_. Questi costruttori convertono codice procedurale in `ZIO` _effects_ con l'intento di separare il "cosa" dal "come". 

#### ZIO.attempt

Il costruttore `ZIO.attempt` permette di convertire codice procedurale in uno `ZIO` _effect_ che cattura i _side effects_, posticipandone la valutazione. Ogni eccezione generata viene convertita in uno `ZIO.fail`. Questo costruttore trova spesso impiego in caso di codice _legacy_ che potrebbe produrre delle eccezioni. 

L'utilizzo di `ZIO.attempt` è limitato poiché: assume che il codice da convertire sia sincrono, non è in grado di gestire tipi di dato che ne contengono altri (es. `Future[A]`) e, fallendo con qualsiasi tipologia di `Throwable`, non permette una gestione degli errori a grana fine. `ZIO` permette comunque di gestire i casi appena citati tramite costruttori ad-hoc. Ad esempio, è possibile modellare un _effect_ che non prevede fallimenti, tramite la direttiva `ZIO.succeed`.

#### ZIO.async

Lavorare direttamente con del codice asincrono basato su _callbacks_ può essere problematico poiché può portare ad elevati livelli di codice innestato, che complicano la propagazione dei valori e rendono quasi impossibile la gestione delle risorse in maniera sicura. A tal proposito, tramite il costruttore `ZIO.async`, è possibile convertire porzioni di codice asincrono in _effect_.
```scala
object ZIO {
  def async[R, E, A](
    cb: (ZIO[R, E, A] => Unit) => Any
  ): ZIO[R, E, A] =
    ???
}
```
Considerando la funzione asincrona `getPostByIdAsync`, e tralasciando gli aspetti implementativi, questa può essere convertita dal costrutto `ZIO.async` come segue:
```scala
def getPostByIdAsync(id: Int)(cb: Option[String] => Unit): Unit =
  ???

def getPostById(id: Int): ZIO[Any, None.type, String] = 
  ZIO.async { cb =>
    getPostByIdAsync(id) {
      case Some(name) => cb(ZIO.succeed(name))
      case None => cb(ZIO.fail(None))
    }
  }
```
Ora la nuova API `getPostById` può essere tratta come una qualsiasi funzione di ZIO, quindi oltre ad essere componibile e sicura, libera il programmatore dalla gestione delle _callbacks_.

#### ZIO.fromFuture

Rispetto ai _functional effect_, come `ZIO`, una `Future` modella un _running effect_ e non sospende la valutazione del codice racchiuso al suo interno. Quindi se una `Future` sta già eseguendo non può essere posticipata, ed in caso di errore non può essere rieseguita. Inoltre ogni qualvolta venga eseguito un metodo della `Future`, è necessario specificare l'`Execution Context`, rendendo impossibile la separazione del "cosa" dal "come". 

Una `Future` può fallire con un qualsiasi `Throwable`, quindi possiede un potere espressivo minore rispetto a quello offerto dall'_error type_ di `ZIO`. Se si considera la funzione `onComplete`:
```scala
trait Future[+A] {
  def onComplete[B](f: Try[A] => B): Unit
}
```
La firma del metodo non fornisce nessuna indicazione sulla possibilità e modalità di fallimento.

`ZIO.fromFuture` permette di convertire una funzione che crea una `Future` in uno `ZIO` _effect_ evitando il passaggio immediato del contesto di esecuzione.
```scala
def fromFuture[A](make: ExecutionContext => Future[A]): Task[A] = 
  ???
```

## Standard `ZIO` Services

I _service_ di `ZIO` forniscono delle interfacce che possono essere concretizzate diversamente in base all'ambiente di _testing_ o _production_. La definizione delle funzionalità mediante interfacce permette di posticipare gli aspetti implementativi, fornendo allo sviluppatore una maggiore flessibilità. Inoltre l'impiego degli `ZIO` _service_ rende il codice autoesplicativo, cioè permette di documentare l'intento e le caratteristiche di un _effect_ attraverso il suo tipo. Nel caso di un _effect_ di tipo `ZIO[Clock, Nothing, Unit]` si può dedurre che non sono previsti fallimenti e che e che il servizio si avvale di funzionalità legate al tempo e/o _scheduling_.

### Clock

Il _Clock service_ fornisce funzionalità relative al tempo e allo _scheduling_ come, per esempio, quelle che consentono di ottenere il tempo corrente in diversi formati. Tramite il metodo `sleep` del servizio è possibile implementare l'operatore `delay` come segue:
```scala
def delay[R, E, A](zio: ZIO[R, E, A])(
    duration: Duration
  ): ZIO[R with Clock, E, A] =
    Clock.sleep(duration) *> zio
```
Coerentemente con la filosofia ZIO, il metodo `sleep` non è bloccante.

### Console

Come si evince dal nome, il _Console service_ espone funzionalità legate alla scrittura e alla lettura da _console_. Come nel caso precedente, il vantaggio di operare mediante interfaccia è quello di poter fornire implementazioni alternative sulla base dell'ambiente di test considerato. I metodi principali di `Console` sono `readLine` e `printLine`:
```scala
object Console {
  val readLine: ZIO[Any, IOException, String]
  def putStr(line: => String): URIO[Any, Unit]
  def printLine(line: => String): URIO[Any, Unit]
}
```

### System

Il _System service_ mette a disposizione funzionalità in grado di recuperare variabili d'ambiente e di sistema. In questo caso i metodi principali possono essere riassunti in `env` (abilita l'accesso alle variabili d'ambiente), e `property` (consente di accedere ad una proprietà di sistema):
```scala
object System {
  def env(
    variable: String
  ): ZIO[System, SecurityException, Option[String]]
  def property(
    prop: String
  ): ZIO[System, Throwable, Option[String]]
}
```

### Random

Il _Random service_ descrive funzionalità legate alla generazione casuale di numeri. `Random` espone essenzialmente la stessa interfaccia di `scala.util.Random`, ma tutti i metodi ritornano _functional effect_.  