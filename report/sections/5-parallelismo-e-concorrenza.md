# Parallelismo e concorrenza

Il capitolo seguente approfondirà il supporto fornito da `ZIO` per quanto riguarda la programmazione concorrente, parallela e asincrona. Partendo dal modello proposto da `ZIO`, si proseguirà con un approfondimento sugli operatori e si concluderà con la presentazione delle strutture concorrenti fondamentali per il coordinamento e lo scambio di informazioni tra processi concorrenti.

## Il modello Fiber

Il supporto di ZIO per il parallelismo e la concorrenza si basa sul modello _Fibers_. Le _fiber_ sono un'alternativa ai _thread_, infatti le istruzioni all'interno di una singola fibra vengono eseguite sequenzialmente, ma a differenza dei _thread_, sono più leggere (minor costo in termini di risorse e _context switching_) e possono essere interrotte in maniera sicura senza la necessità di bloccarsi. Inoltre, essendo le _fiber_ semplicemente un tipo di dato, consentono una gestione a grana fine degli errori, possono essere composte tra loro e possono essere eseguite a milioni contemporaneamente. La stessa cosa non si può dire per i _thread_, dato che accettano una funzione che ritorna `void` e catturano solamente eccezioni di tipo `Throwable`.

Quando viene chiamato il metodo `unsafeRun`, il runtime di `ZIO` delega l'esecuzione del programma a una `Fiber` che opera all'interno di uno specifico `Executor` (realizzato tramite un _thread pool_). La scelta dell'`Executor` consente di definire la modalità di interruzione delle _fiber_ e di modellare una logica che garantisca il rispetto della proprietà di _fairness_.

Inoltre, una _fiber_ non può determinare a priori il _thread_ che la eseguirà, infatti questo può cambiare a _runtime_, soprattutto nel caso di attività di lunga durata, può cambiare.

### Operazioni principali

Le operazioni fondamentali possono essere riassunte in:

- `fork`: crea una nuova _fiber_, a partire da quella corrente, in grado di eseguire in maniera concorrente.
  ```scala
  trait ZIO[-R, +E, +A] {
    def fork: URIO[R, Fiber[E, A]]
  }
  ```
- `join`: ritorna il risultato di una computazione concorrente alla _fiber_ corrente. Il tutto avviene in maniera non bloccante, infatti internamente il runtime di `ZIO` registra una _callback_ che viene eseguita quando il risultato atteso è pronto. In questo modo l'`Executor` può occuparsi di altre _fiber_ evitando la perdita di risorse e tempo;
  ```scala
  trait Fiber[+E, +A] {
    def join: IO[E, A]
  }
  ```
- `await`: simile a `join` ma consente di gestire il risultato sia nel caso di successo che in quello di fallimento;
  ```scala
  trait Fiber[+E, +A] {
    def await: UIO[Exit[E, A]]
  }
  ```
- `poll`: permette di osservare lo stato della _fiber_ senza bloccarsi;
  ```scala
  trait Fiber[+E, +A] {
    def poll: UIO[Option[Exit[E, A]]]
  }
  ```
- `interrupt`: interrompe immediatamente una _fiber_ ritornandone il risultato se è già completata, oppure fallendo con una `Cause.Interrupt`. Il metodo provoca l'esecuzione di tutti i _finalizzatori_ associati, così da lasciare il sistema in uno stato consistente.
  ```scala
  trait Fiber[+E, +A] {
    def interrupt: UIO[Exit[E, A]]
  }
  ```

### Interruzione nel dettaglio

Il _runtime_ di `ZIO` non ha modo di conoscere preventivamente la complessità delle istruzioni da eseguire, di conseguenza verificherà la presenza o meno di interruzioni prima ancora di eseguire l'_effect_. Quindi l'_effect_ potrebbe essere interrotto a priori, soprattutto nel caso in cui il suo risultato non fosse utilizzato.
```scala
for {
  fiber <- ZIO.succeed(println("Hello, World!")).fork
  _     <- fiber.interrupt
} yield ()
```
Nell'esempio proposto, la stampa della frase "Hello World!" potrebbe non avvenire mai.

Un'ulteriore implicazione è che, in assenza di una specifica logica, il _runtime di_ `ZIO` non è in grado di interrompere blocchi di codice importato che racchiudo _side effect_. Ad esempio, considerando un programma che stampa i numeri compresi tra `1` e `100000`:
```scala
val myEffect: UIO[Unit] =
  UIO.succeed {
    var i = 0
    while (i < 100000) {
      println(i)
      i += 1
    }
  }

for {
  fiber <- myEffect.fork
  _     <- fiber.interrupt
} yield ()
```
In questo caso `myEffect`, una volta eseguito, non potrà essere interrotto perché le interruzioni possono avvenire solamente tra un'istruzione e l'altra. Di solito questo non è un problema perché molti dei programmi `ZIO` consistono in tante piccole espressioni concatenate dall'operatore `flatMap`, quindi rapidamente interrompibili. In alcune situazioni, quasi sempre per motivi di efficienza, può essere necessario eseguire un unico _effect_ monolitico. `ZIO` permette comunque la sua interruzione tramite appositi costrutti come `attemptBlockingCancelable`:
```scala
def attemptBlockingCancelable(
  effect: => A
)(cancel: UIO[Unit]): Task[A] =
  ???
```
La funzione essenzialmente descrive un _effect_ che può richiedere molto tempo, la cui logica di terminazione viene fornita dal chiamante (`cancel`). Il programma precedente può essere reso interrompibile nel seguente modo:
```scala
import java.util.concurrent.atomic.AtomicBoolean
import zio.blocking._
def effect(canceled: AtomicBoolean): Task[Unit] =
  ZIO.attemptBlockingCancelable(
    {
      var i = 0
      while (i < 100000 && !canceled.get()) {
        println(i)
        i += 1
      }
    },
    UIO.succeed(canceled.set(true))
  )

for {
  ref   <- ZIO.succeed(new AtomicBoolean(false))
  fiber <- effect(ref).fork
  _     <- fiber.interrupt
} yield ()
```
Adesso l'_effect_ può essere interrotto in maniera sicura, anche durante l'esecuzione, impostando l'`AtomicBoolean` a `true` e causando la rottura del ciclo `while`.

Qualsiasi porzione di un _effect_ può essere sia _interrompibile_ (default), che _non interrompibile_, a tal proposito `ZIO` mette a disposizione degli operatori per la loro gestione:
```scala
val uninterruptible: UIO[Unit] =
  UIO(println("Doing something really important"))
    .uninterruptible

val interruptible: UIO[Unit] =
  UIO(println("Feel free to interrupt me if you want"))
    .interruptible
```
Come per le _fiber_, anche gli operatori `uninterruptible` e `interruptible` sono associati a delle regole di _scope_:

1. i combinatori proposti si applicano all'intero _scope_ in cui sono eseguiti. Ad esempio, nel caso di `(zio1 *> zio2 *> zio3).uninterruptible`, tutti e tre gli _effect_ saranno non interrompibili;
2. gli _scope_ interni hanno precedenza su quelli esterni. Nel caso di `(zio1 *> zio2.interruptible *> zio3).uninterruptible`, data la precedenza, `zio2` è interrompibile. 

Anche l'operazione `interrupt` può richiedere del tempo per completare. Per esempio, se all'_effect_ sono stati associati dei _finalizzatori_, `interrupt` può ritornare solo una volta che questi hanno terminato la propria esecuzione. L'unico modo per evitare di attendere il completamento di `interrupt`, è eseguire quest'ultimo in modo concorrente tramite `fork`.
```scala
for {
  promise <- Promise.make[Nothing, Unit]
  effect = promise.succeed(()) *> ZIO.never
  finalizer = ZIO.succeed(println("Closing file"))
    .delay(5.seconds)
  fiber <- effect.ensuring(finalizer).fork
  _ <- promise.await
  _ <- fiber.interrupt.fork
  _ <- ZIO.succeed(println("Done interrupting"))
} yield ()
```
Nell'esempio proposto, se non si fosse utilizzato l'operatore `fork` anche per l'interruzione, `interrupt` avrebbe avuto effetto solo a seguito dei `5` secondi e della stampa a video (_"Closing file"_). Mentre eseguendo `fork` in maniera concorrente, prima verrà stampato _"Done interrupting"_ e `5` secondi dopo anche _"Closing file"_.

Infine, marcando un _effect_ con l'operatore `disconnect` si evita di attendere il completamento dei _finalizzatori_ associati, durante l'attività di interruzione. Considerando due _effect_, `a` e `b`, eseguiti parallelamente, nel caso in cui il secondo venisse disconnesso, la funzione `interrupt` attenderebbe solo la terminazione di `a`:
```scala
val a: URIO[Clock, Unit] =
  ZIO.never
    .ensuring(ZIO.succeed("Closed A")
    .delay(3.seconds))

val b: URIO[Clock, Unit] =
  ZIO.never
  .ensuring(ZIO.succeed("Closed B")
  .delay(5.seconds))
  .disconnect

for {
  fiber <- (a <&> b).fork
  _ <- Clock.sleep(1.second)
  _ <- fiber.interrupt
  _ <- ZIO.succeed(println("Done interrupting"))
} yield ()
```

### Supervisione delle Fiber

Similmente a quanto succede in un _modello ad attori_, in cui il padre supervisiona i figli, anche `ZIO` implementa un **modello di supervisione delle _fiber_**:

1. ogni _fiber_ è associata ad uno ***scope***;
2. ogni _fiber_, di default, viene avviata all'interno dello _scope_ del padre;
3. lo _scope_ di una _fiber_ viene chiuso a seguito della sua terminazione per successo, errore o interruzione;
4. la chiusura di uno _scope_ provoca la terminazione di tutte le _fiber_ al suo interno.

L'implicazione di questo modello è che, di base, le _fiber_ non possono vivere al di fuori del padre. Nel caso in cui fosse necessario creare un processo indipendente da quello padre, è possibile effettuare il `fork` di una _fiber_ nello _scope_ globale, tramite il metodo `forkDaemon`.
```scala
trait ZIO[-R, +E, +A] {
  def forkDaemon: URIO[R, Fiber[E, A]]
}
```

Di solito le _fiber_ vengono eseguite all'interno dell'`Executor` di default di `ZIO`, ma questo può essere modificato tramite le funzioni `lock` e `on`:
```scala
import scala.concurrent.ExecutionContext
trait ZIO[-R, +E, +A] {
  def lock(executor: Executor): ZIO[R, E, A]
  def on(executionContext: ExecutionContext): ZIO[R, E, A]
}
```
L'operatore `lock` è quello maggiormente impiegato, e permette di scegliere l'`Executor` dentro il quale sarà eseguito l'_effect_. Inoltre `lock` stabilisce delle regole:

1. assegnare (_lock_) un _effect_ a un certo `Executor`, implica assegnare tutte le sue parti;
2. gli _scope_ interni hanno precedenza su quelli più esterni.

La seconda regola può essere esplicitata mediante il seguente esempio:
```scala
lazy val executor1: Executor = ???
lazy val executor2: Executor = ???

lazy val effect2 = for {
  _ <- doSomething.onExecutor(executor2).fork
  _ <- doSomethingElse
} yield ()

lazy val result2 = effect2.onExecutor(executor1)
```
In questo caso, `doSomething` verrà sicuramente eseguito all'interno di `executor2`, mentre `doSomethingElse` all'interno di `executor1`.

## Operatori concorrenti

Anche per quanto riguarda la concorrenza, `ZIO` offre diversi operatori che permettono di astrarre dagli aspetti legati alle _fiber_, al fine di supportare il programmatore durante lo sviluppo di programmi concorrenti. Le funzioni di base sono `raceEither` e `zipPar`, entrambe rappresentano delle operazioni binarie che combinano due valori:

- `zipPar`: permette di eseguire due _effect_ (`self` e `that`) parallelamente, restituendo al termine i risultato di entrambi; 
- `raceEither`: equivalente a `zipPar` ma il risultato restituito è quello del primo _effect_ che termina con successo. Il secondo, cioè quello ancora in esecuzione, sarà interrotto poiché non più necessario.

```scala
trait ZIO[-R, +E, +A] { self =>
  def raceEither[R1 <: R, E1 >: E, B](
    that: ZIO[R1, E1, B]
  ): ZIO[R1, E1, Either[A, B]]

  def zipPar[R1 <: R, E1 >: E, B](
    that: ZIO[R1, E1, B]
  ): ZIO[R1, E1, (A, B)]
}
```

Il combinatore `zipPar` può essere utilizzato per implementare altri operatori tra cui `foreachPar` e `collectAllPar`, versioni parallele rispettivamente di `foreach` e `collectAll`.

## Strutture concorrenti: Ref - Shared State

Molto spesso lo sviluppo di programmi concorrenti richiede la condivisione di uno stato tra i processi (_fiber_), al fine di scambiare informazioni. In `ZIO` questa necessità viene soddisfatta dalla struttura `Ref`, che può essere vista come un'alternativa puramente funzionale all'`AtomicReference`. In linea con la filosofia di `ZIO`, `Ref` consente di fornire una descrizione di modifica di stato. In questo modo è possibile modellare programmi che rispettano la _trasparenza referenziale_ così da permettere al programmatore di ragionare sull'applicativo mediante _substitution model_.
```scala
trait Ref[A] {
  def modify[B](f: A => (B, A)): UIO[B]
  def get: UIO[A] =
    modify(a => (a, a))
  def set(a: A): UIO[Unit] =
    modify(_ => ((), a))
  def update[B](f: A => A): UIO[Unit] =
    modify(a => ((), f(a)))
}

object Ref {
  def make[A](a: A): UIO[Ref[A]] = 
    ??? 
}
```
Ogni operatore di `Ref` restituisce uno `ZIO` che descrive una modifica dello stato di `Ref` stesso. Un semplice esempio applicativo:
```scala
def increment(ref: Ref[Int]): UIO[Unit] =
  for {
    n <- ref.get
    _ <- ref.set(n + 1)
  } yield ()
```
Il metodo `increment` riceve un `Ref` in input e restituisce un _effect_ che ne descrive l'incremento.

`Ref` oltre ad essere un'alternativa puramente funzionale, si integra con la programmazione concorrente poiché l'accesso allo stato, da parte di più processi, avviene in maniera sicura. `Ref` ha però un limite, dalla prospettiva del chiamante, `update` legge sempre il vecchio valore e scrive quello nuovo in una singola "transazione" garantendo atomicità. Questo non è assicurato nel caso in cui `Ref` venga combinato con una serie di operatori. Per esempio, il comportamento `ref.get.flatMap(n => ref.set(n + 1))` è imprevedibile perché molteplici _fiber_, eseguendo contemporaneamente, possono leggere lo stesso valore provocando la perdita di alcuni aggiornamenti. Delle buone pratiche da seguire per ovviare a questi tipi di problemi:

- inserire tutte le porzioni di uno stato che devono essere consistenti tra loro all'interno di una singola `Ref`;
- modificare una `Ref` sempre tramite una singola operazione;
- utilizzare sempre gli operatori `modify` e `update` al posto di `get` e `set` perché, essendo singole operazioni, garantiscono automaticamente l'atomicità.

Alternativamente, è possibile comporre operazioni in una `Ref` tramite la funzionalità **STM** (Software Transactional Memory) di `ZIO`.

Nel caso di `modify`, il parametro passatogli in input deve essere una funzione pura, cioè non deve produrre _side effect_, in questo modo si otterrebbe sempre lo stesso stato indipendentemente dal numero di esecuzioni. Se si volesse fornire a `modify` una funzione `f` che produce _side effect_, si dovrebbe ricorrere al tipo di dato `Ref.Synchronized`.  
```scala
trait Synchronized[A] {
  def modifyZIO[R, E, B](f: A => ZIO[R, E, (B, A)]): ZIO[R, E, B]
}
```
Internamente `Synchronized` è implementato tramite un semaforo (`Semaphore`) così da garantire che solo una _fiber_ alla volta possa interagire con la `Ref`.

### Stato locale alla Fiber: FiberRef

In caso di necessità, tramite la struttura `FiberRef` è possibile mantenere uno stato interno ad ogni _fiber_. `FiberRef`, oltre a comporsi di un valore iniziale, come nel caso di `Ref`, definisce due operazioni:
 
- `fork`: descrive l'eventuale trasformazione che la variabile `ref`, della _fiber_ padre, deve subire prima di essere copiata nel figlio;
- `join`: descrive in che modo il valore della `ref` padre sarà combinato con quello del figlio.

```scala
def make[A](
  initial: A,
  fork: A => A,
  join: (A, A) => A
): UIO[FiberRef[A]] =
  ???
```
Un esempio applicativo può essere la realizzazione di un _logger_ che permette di visualizzare in che modo le computazioni vengono parallelizzate (`fork`) e riunite (`join`).
```scala
final case class Tree[+A](head: A, tail: List[Tree[A]])

type Log = Tree[Chunk[String]]

val loggingRef: ZIO[Scope, Nothing, FiberRef[Log]] =
  FiberRef.make[Log](
    Tree(Chunk.empty, List.empty),
    _ => Tree(Chunk.empty, List.empty),
    (parent, child) => parent.copy(tail = child :: parent.tail)
  )

def log(ref: FiberRef[Log])(string: String): UIO[Unit] =
  ref.update(log => log.copy(head = log.head :+ string))
  for {
    ref <- loggingRef
    left = for {
      a <- ZIO.succeed(1).tap(_ => log(ref)("Got 1"))
      b <- ZIO.succeed(2).tap(_ => log(ref)("Got 2"))
    } yield a + b
    right = for {
      c <- ZIO.succeed(1).tap(_ => log(ref)("Got 3"))
      d <- ZIO.succeed(2).tap(_ => log(ref)("Got 4"))
    } yield c + d
    fiber1 <- left.fork
    fiber2 <- right.fork
    _ <- fiber1.join
    _ <- fiber2.join
    log <- ref.get
    _ <- Console.printLine(log.toString)
  } yield ()
```
`Tree` è una struttura dati che permette di catturare l'albero delle _fiber_ create, anche in maniera ricorsiva, la cui variabile `head` rappresenta un _log_ di una specifica _fiber_. Una volta eseguito il programma, sarà generato un albero composto da tre nodi: un padre e due figli. Ques'ultimi inseriranno reciprocamente nel _log_ `"Got1"`, `"Got2"` e `"Got3"`, `"Got4"`. 

## Strutture concorrenti: Promise - Work synchronization

`Ref` consente di condividere lo stato tra diverse _fiber_, ma non può essere utilizzato come meccanismo di sincronizzazione. A tal proposito, `ZIO` dispone di un'altra struttura concorrente chiamata `Promise`; di seguito viene fornita una rappresentazione semplificata della sua interfaccia.
```scala
trait Promise[E, A] {
  def await: IO[E, A]
  def fail(e: E): UIO[Boolean]
  def succeed(a: A): UIO[Boolean]
}
```
Una `Promise` può essere vista come un contenitore, pieno o vuoto, che racchiude una _failure_ `E` oppure un valore `A` e che può essere completata, tramite `fail` o `succeed`, solamente una volta. Il risultato di una `Promise` può essere recuperato mediante l'operatore "bloccante" `await`.  

Un semplice utilizzo della `Promise` ai fini di sincronizzazione:
```scala
for {
  promise <- Promise.make[Nothing, Unit]
  left    <- (Console.print("Hello, ") *> promise.succeed(())).fork
  right   <- (promise.await *> Console.print("World!")).fork
  _       <- left.join *> right.join
} yield ()
```
Il programma proposto è deterministico, cioè a video verrà visualizzato sempre `"Hello World"`. Quindi si può concludere che la `Promise` impone un'ordinamento lineare tra porzioni di codice concorrente.

### Promise: completamento

Esistono altri modi per completare una `Promise`:

- `die`: completa la `Promise` con un _effect_ che termina con un `Throwable`;
- `done`: completa la `Promise` con un _effect_ associato ad uno specifico `Exit`;
- `failCause`: come `die` ma l'_effect_ è associato ad una specifica `Cause`;
- `complete`: completa una `Promise` con il risultato di un _effect_ passatogli in input;
- `completeWith`: come il precedente, ma il risultato ritornato è l'_effect_ stesso, quindi questo sarà valutato ogni volta che viene chiamato `await`.

```scala
trait Promise[E, A] {
  def die(t: Throwable): UIO[Boolean]
  def done(e: Exit[E, A]): UIO[Boolean]
  def failCause(e: Cause[E]): UIO[Boolean]

  def complete(io: IO[E, A]): UIO[Boolean]
  def completeWith(io: IO[E, A]): UIO[Boolean]
}
```

### Promise: attesa

`ZIO` offre diversi metodi per osservare lo stato di una `Promise`:

- `await`: sospende la _fiber_ corrente fino al completamento della `Promise`, e ne ritorna l'_effect_;
- `isDone`: permette di controllare se la `Promise` è stata completata;
- `poll`: ritorna un `Option[IO[E, A]]` che varrà `None` se la `Promise` non è stata ancora completata, oppure `Some` in caso contrario.
<!-- se l'_effect_ è pronto per essere restituito. -->
```scala
trait Promise[E, A] {
  def await: IO[E, A]
  def isDone: UIO[Boolean]
  def poll: UIO[Option[IO[E, A]]]
}
```

### Promise: interruzione

Le _Promise_ supportano il modello di interruzione di `ZIO`. Ciò significa che il completamento di una `Promise`, il cui risultato è un _effect_ che è stato interrotto, causerà l'interruzione di tutte le _fiber_ in attesa su quella `Promise`. 

Una `Promise` può essere interrotta anche manualmente tramite le funzioni `interrupt` e `interruptAs`.
```scala
trait Promise[E, A] {
  def interrupt: UIO[Boolean]
  def interruptAs(fiberId: FiberId): UIO[Boolean]
}
```
Tramite il parametro `fiberId` di `interruptAs` è possibile specificare la _fiber_ che ha richiesto l'interruzione.

## Strutture concorrenti: Queue - Work distribution

Similmente ad una `Promise`, una `Queue` consente alle _fiber_ di sospendersi al fine di attendere l'inserimento o l'estrazione di un valore della coda. A differenza della prima, la `Queue` permette di gestire molteplici valori, infatti il suo scopo principale è quello di distribuire del lavoro tra le _fiber_. Ovviamente la struttura garantisce un accesso sicuro ai valori in caso di concorrenza.

Le operazioni fondamentali di una `Queue` sono `offer` e `take`:
```scala
trait Queue[A] {
  def offer(a: A): UIO[Boolean]
  def take: UIO[A]
}
```
La prima consente di inserire un valore nella coda, mentre `take` sospende (blocca semanticamente) la _fiber_ corrente finché non è presente un valore.

### Queue: varianti

Le due principali tipologie di code sono quella **`unbounded`**, che non ha una capacità massima, e quella **`bounded`** che può contenere un numero limitato di valori (_capacity_). Quest'ultima permette di prevenire problemi legati alla memoria (_memory leak problem_), ma richiede di specificare la logica di gestione degli inserimenti nel caso in cui la coda fosse piena

A tal proposito, `ZIO` definisce tre strategie:

- ***Back Pressure***: sospende il chiamante del metodo `offer` finché la coda è piena. Questa strategia evita la perdita di informazioni, ma obbliga i _consumers_ ad elaborare tutti i valori prima di raggiungere i più recenti;
  ```scala
  for {
    queue <- Queue.bounded[String](2)
    _     <- queue.offer("ping")
                  .tap(_ => Console.printLine("ping"))
                  .forever
                  .fork
  } yield ()
  ```
- ***Sliding Strategy***: in questo caso, l'inserimento di un valore in una coda già piena provocherà la rimozione del primo elemento della `Queue`. Questa strategia deve essere applicata quando i valori più recenti hanno la priorità. Per realizzare una coda scorrevole ci si avvale del costruttore `sliding`.
  ```scala
  for {
    queue <- Queue.sliding[Int](2)
    _     <- ZIO.foreach(List(1, 2, 3))(queue.offer)
    a     <- queue.take
    b     <- queue.take
  } yield (a, b)
  ``` 
  In questo esempio i valori ritornati saranno sempre gli ultimi due (`2` e `3`).
- ***Dropping Strategy***: la richiesta di inserimento verrà scartata nel caso in cui la coda abbia raggiunto la capacità massima. In questa strategia l'obiettivo è mantenere una distribuzione dei valori piuttosto che avere quelli più recenti, come nel caso dei dati meteorologici. La strategia in questione può essere realizzata avvalendosi del costruttore `dropping`.
  ```scala
  for {
    queue <- Queue.dropping[Int](2)
    _     <- ZIO.foreach(List(1, 2, 3))(queue.offer)
    a     <- queue.take
    b     <- queue.take
  } yield (a, b)
  ```

### Queue: chiusura

Nel caso in cui una `Queue` non fosse più necessaria, è possibile chiuderla tramite il metodo `shutdown`. La chiusura implica l'interruzione delle _fiber_ in attesa sulla coda.
```scala
trait Queue[A] {
  def awaitShutDown: UIO[Unit]
  def isShutdown: UIO[Boolean]
  def shutdown: UIO[Unit]
}
```
Come intuibile dal nome, `isShutdown` verifica se la coda è stata chiusa, mentre `awaitShutdown` permette di attendere la chiusura.

## Strutture concorrenti: Hub - Broadcasting

Una classe di problema meno diffusa rispetto a quella di distribuzione del lavoro, è il _broadcasting_: ogni valore comunicato deve essere ricevuto da tutti i _consumer_. La soluzione di `ZIO` a tale problema, è la struttura dati `Hub`, la quale assegna ad ogni _consumer_ uno specifico indice, così che i _consumer_ accedano a posizioni distinte della struttura. Un `Hub` è definito in termini di due operazioni fondamentali:

- `publish`: similmente a a quanto succede con il metodo `offer` di `Queue`, la funzione consente di pubblicare un valore all'interno della struttura;
- `subscribe`: permette di ottenere i valori che attraversano l'`Hub` sotto forma di `Dequeue`. I _consumer_ continueranno a ricevere valori fintanto che sono sottoscritti.

```scala
sealed abstract class Hub[A] {
  def publish(a: A): UIO[Boolean] = ???
  def subscribe: ZIO[Scope, Nothing, Dequeue[A]] = ???
}
```
Concettualmente, ogni _consumer_ interagisce con la struttura come se fosse un canale le cui estremità vengono rispettivamente rappresentate dalle interfacce `Enqueue` e `Dequeue`.

### Tipologie di Hub

Come nel caso delle code, anche gli _hub_ possono essere ***bounded*** e ***unbounded***. La tipologia più comune è quella _bounded_ con strategia _back pressure_, e può essere implementata tramite l'operatore `bounded`:
```scala
object Hub {
  def bounded[A](requestedCapacity: Int): UIO[Hub[A]] =
    ???
}
``` 
Idealmente, questo tipo di `Hub` può essere rappresentato tramite un vettore contenente i valori da comunicare. Ogni _consumer_ richiede i valori utilizzato l'indice preventivamente assegnatoli, quando tutti i _consumer_ hanno letto lo stesso valore, quest'ultimo viene rimosso dal vettore. Un `Hub` di tipo _bounded_ è la soluzione di default perché:

- garantisce che ogni _consumer_ riceva ogni valore pubblicato, quindi i valori non verranno mai scartati;
- previene problemi di _memory leaks_ causati da un _producer_ troppo veloce rispetto ai _consumer_;
- propaga la strategia di _back pressure_ lungo tutto il sistema.

Un'altra variante di `Hub` è quella di tipo ***sliding*** che consente di liberare spazio eliminando i dati meno recenti. Questa tipologia garantisce il completamento immediato dell'attività di pubblicazione (_publishing_) dei valori.
```scala
object Hub {
  def sliding[A](requestedCapacity: Int): UIO[Hub[A]] =
    ???
}
``` 

Infine, l'ultima variante è quella di tipo ***dropping***, in cui la pubblicazione di un valore, in mancanza di spazio, provoca il fallimento dell'operazione.
```scala
object Hub {
  def dropping[A](requestedCapacity: Int): UIO[Hub[A]] =
    ???
}
```

### Operazioni

Oltre la creazione, ZIO fornisce altre operazioni per interagire con l'`Hub`, di seguito viene presentata l'interfaccia completa.
```scala
sealed trait Hub[A] {
  def awaitShutdown: UIO[Unit]
  def capacity: Int
  def isShutdown: UIO[Boolean]
  def publish(a: A): UIO[A]
  def publishAll(as: Iterable[A]): UIO[Boolean]
  def shutdown: UIO[Unit]
  def size: UIO[Int]
  def subscribe: ZIO[Scope, Nothing, Dequeue[A]]
  def toQueue: Enqueue[A]
}
```
Proprio come una `Queue`, anche l'`Hub` può essere chiuso (`shutdown`) causando l'interruzione immediata di qualsiasi _fiber_ che tenterà di inserire o ricevere tramite sottoscrizione un valore dalla struttura. Inoltre è possibile conoscere il numero di messaggi presenti all'interno dell'`Hub` sfruttando l'operatore `size`. Infine la funzione `toQueue` consente di interagire con la struttura come se fosse una coda su cui è possibile solo scrivere. 


## Strutture concorrenti: Semaphore - Work limiting

Un `Semaphore` è una struttura che possiede un certo numero di "permessi", specificato all'atto di creazione, ed è definito attraverso un unico metodo denominato `withPermits`.
```scala
trait Semaphore {
  def withPermits[R, E, A](n: Long)
    (task: ZIO[R, E, A]): ZIO[R, E, A]
}
```
Ogni _fiber_ che vuole eseguire il blocco di codice di _withPermits_ deve prima "acquisire" un permesso. Se il numero dei permessi è maggiore di zero allora il contatore viene decrementato e la _fiber_ può procedere con l'esecuzione dell'attività. In caso contrario, la _fiber_ si sospenderà finché i permessi non ritorneranno disponibili. 

La struttura `Semaphore` permette quindi di limitare il grado di concorrenza, cioè il numero massimo di _fiber_ che accedono contemporaneamente a un certo blocco di codice. 

Un caso d'uso comune del `Semaphore` è la creazione di strutture dati _concurrently safe_. L'idea di base è quella di implementare un `Semaphore` con un solo permesso, così che il blocco di codice protetto possa essere eseguito da una sola _fiber_ alla volta. In questo modo un `Semaphore` agisce come una _lock_ o un _synchronized block_ della programmazione concorrente tradizionale, evitando però di bloccare i _thread_ di sistema. 

Un esempio applicativo può essere la creazione di una `Ref` protetta da un semaforo.
```scala
trait RefM[A] {
  def modify[R, E, B](f: A => ZIO[R, E, (B, A)]): 
    ZIO[R, E, B]
}

object RefM {
  def make[A](a: A): UIO[RefM[A]] =
    for {
      ref <- Ref.make(a)
      semaphore <- Semaphore.make(1)
    } yield new RefM[A] {
      def modify[R, E, B](
        f: A => ZIO[R, E, (B, A)]
      ): ZIO[R, E, B] =
        semaphore.withPermit {
          for {
          a <- ref.get
          v <- f(a)
          _ <- ref.set(v._2)
        } yield v._1
      }
    }
}
```
Come mostrato nell'esempio, l'implementazione del metodo `modify` della `Ref` è racchiuso all'interno di `withPermit`. In questo modo se due _fiber_ tentano di modificare in maniera concorrente la `Ref`, la prima otterrà il permesso e potrà eseguire l'_effect_ che aggiornerà lo stato, mentre la seconda si sospenderà.


