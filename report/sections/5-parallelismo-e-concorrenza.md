# Parallelismo e concorrenza

## Il modello Fiber

Il supporto di ZIO per il parallelismo e la concorrenza si basa sul modello _Fibers_. Le _fibers_ sono un alternativa ai _thread_, infatti le istruzioni all'interno di una singola fibra vengono eseguite sequenzialmente, ma a differenza dei _thread_, sono più leggere (minor costo in termini di risorse e _context switching_) e possono essere interrotte e/o unite in maniera sicura senza la necessità bloccarsi. Inoltre siccome le _fiber_ sono un tipo di dato, possono essere composte, abilitano al tracciamento degli errori ed è possibile eseguirne a milioni. La stessa cosa non si può dire per i _thread_, dato che accettano una funzione che ritorna `void` e catturano solamente eccezioni di tipo `Throwable`.

Quando viene chiamato il metodo `unsafeRun`, il runtime di ZIO delega l'esecuzione del programma a una `Fiber` che opera all'interno di uno specifico `Executor` (realizzato tramite un _thread pool_). La scelta di quest'ultimo permette di modellare una logica che garantisca _fairness_, e consente di scegliere la modalità di interruzione delle singole _fiber_.

Inoltre, le _fiber_ non forniscono garanzie sul _thread_ sul quale verranno eseguite, infatti a _runtime_ il _thread_, soprattutto nel caso di attività di lunga durata, può cambiare.

### Operazioni principali

Le operazioni fondamentali possono essere riassunte in:

- `fork`: crea una nuova _fiber_, a partire da quella corrente, in grado di eseguire in maniera concorrente.
  ```scala
  trait ZIO[-R, +E, +A] {
    def fork: URIO[R, Fiber[E, A]]
  }
  ```
- `join`: ritorna il risultato di una computazione concorrente alla _fiber_ corrente. Il tutto avviene in maniera non bloccante, infatti internamente il runtime di ZIO registra una _callback_ che viene eseguita quando il risultato atteso è pronto. In questo modo l'`Executor` può occuparsi di altre _fiber_ evitando la perdita di risorse e tempo;
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

Il _runtime_ di ZIO non ha modo di conoscere la complessità delle istruzioni da eseguire, proprio per questo il controllo sull'interruzione avviene prima ancora dell'esecuzione delle istruzioni. Come conseguenza un _effect_ può essere interrotto preventivamente.
```scala
for {
  fiber <- ZIO.succeed(println("Hello, World!")).fork
  _ <- fiber.interrupt
} yield ()
```
Nell'esempio proposto, la frase "Hello World!" non sarà mai stampata a video perché l'istruzione può essere interrotta prima ancora che venga eseguita. 

Un ulteriore implicazione è che, in assenza di una speciale logica, il runtime di ZIO non sa come interrompere singoli blocchi di codice importato con _side effect_. Ad esempio, considerando un programma che stampa i numeri compresi tra `1` e `100000`:
```scala
val effect: UIO[Unit] =
  UIO.succeed {
    var i = 0
    while (i < 100000) {
      println(i)
      i += 1
    }
  }

for {
  fiber <- effect.fork
  _ <- fiber.interrupt
} yield ()
```
In questo caso l'_effect_ non potrà essere interrotto durante la sua esecuzione, perché le interruzioni possono avvenire solamente tra un'istruzione e l'altra, mentre il codice proposto è descritto da una singola espressione. Di solito questo non è un problema perché molti dei programmi ZIO consistono in un elevato numero di piccole istruzioni concatenate dall'operatore `flatMap`, quindi rapidamente interrompibili. In alcune situazioni, quasi sempre per efficienza, può essere necessario eseguire un unico _effect_ monolitico. ZIO permette comunque la sua interruzione tramite costrutti specializzati come `attemptBlockingCancelable`:
```scala
def attemptBlockingCancelable(
  effect: => A
)(cancel: UIO[Unit]): Task[A] =
  ???
```
La funzione essenzialmente descrive un _effect_ che potrebbe richiedere molto tempo, la cui logica di terminazione viene passata in input dal chiamante (azione `cancel`). Il programma precedente, al fine di renderlo interrompibile, può essere riscritto nel seguente modo:
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
  ref <- ZIO.succeed(new AtomicBoolean(false))
  fiber <- effect(ref).fork
  _ <- fiber.interrupt
} yield ()
```
In questo modo l'_effect_ può essere interrotto in maniera sicura, anche durante l'esecuzione, impostando l'`AtomicBoolean` a `true` e causando la rottura del ciclo `while`.

Qualsiasi parte di un _effect_ ZIO può essere sia _interrompibile_ (default), che _non interrompibile_, ZIO mette a disposizione degli operatori di controllo dell'"interrompibilità":
```scala
val uninterruptible: UIO[Unit] =
  UIO(println("Doing something really important"))
  .uninterruptible

val interruptible: UIO[Unit] =
  UIO(println("Feel free to interrupt me if you want"))
  .interruptible
```
Come per le _fiber_, anche gli operatori `uninterruptible` e `interruptible` sono associati a delle regole di _scope_:

1. i combinatori proposti si applicano all'intero _scope_ in cui sono chiamati. Ad esempio, nel caso di `(zio1 *> zio2 *> zio3).uninterruptible`, tutti e tre gli _effect_ saranno non interrompibili;
2. gli _scope_ interni hanno precedenza su quelli esterni. Nel caso di `(zio1 *> zio2.interruptible *> zio3).uninterruptible`, data la precedenza, `zio2` è interrompibile. 

Inoltre l'interruzione non ritorna finché tutta la logica associata all'_effect_ interrotto non ha completato l'esecuzione. Un esempio esplicativo può essere il seguente:
```scala
for {
  promise <- Promise.make[Nothing, Unit]
  effect = promise.succeed(()) *> ZIO.never
  finalizer = ZIO.succeed(println("Closing file"))
    .delay(5.seconds)
  fiber <- effect.ensuring(finalizer).fork
  _ <- promise.await
  _ <- fiber.interrupt
  _ <- ZIO.succeed(println("Done interrupting"))
} yield ()
```
In questo caso l'`interrupt` avrà effetto, cioè completerà, solo passati `5` secondi e una volta stampato _"Closing file"_. L'unico modo per eseguire `interrupt` senza attendere la sua terminazione è marcarlo come concorrente tramite `fork`:
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
Nella seconda versione, prima verrà stampato _"Done interrupting"_ e `5` secondi dopo anche _"Closing file"_.

Infine ZIO consente di evitare di attendere il completamento dell'interruzione di _effect_ grazie all'operatore `disconnect`. Considerando due _effect_, `a` e `b`, nel caso in cui il secondo venisse disconnesso, e fosse chiamato `interrupt`, il programma attenderebbe solo la terminazione di `a`:
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

### Supervisione delle _Fibers_

Similmente a quanto succede in un _modello ad attori_, in cui il padre supervisiona i figli, anche ZIO implementa un **modello di supervisione delle _fibers_**:

1. ogni _fiber_ è associata ad uno ***scope***;
2. ogni _fiber_ viene biforcata all'interno di uno _scope_, che se non specificato, è lo stesso della _fiber_ corrente;
3. lo _scope_ di una _fiber_ viene chiuso a seguito della sua terminazione per successo, errore o interruzione;
4. quando uno _scope_ viene chiuso, tutto le _fiber_ al suo interno terminano.

L'implicazione di questo modello è che, di default, le _fiber_ non possono vivere al di fuori del padre. Nel caso in cui fosse necessario creare un processo indipendente da quello padre, è possibile effettuare il `fork` di una _fiber_ nello _scope_ globale, tramite il metodo `forkDaemon`.
```scala
trait ZIO[-R, +E, +A] {
  def forkDaemon: URIO[R, Fiber[E, A]]
}
```

Infine, normalmente le _fiber_ vengono eseguite all'interno dell'`Executor` di default di ZIO, ma può capitare di dover modificare l'`Executor`, questo è possibile tramite i combinatori `lock` e `on`:
```scala
import scala.concurrent.ExecutionContext
trait ZIO[-R, +E, +A] {
  def lock(executor: Executor): ZIO[R, E, A]
  def on(executionContext: ExecutionContext): ZIO[R, E, A]
}
```
L'operatore `lock` è quello più utilizzato dei due, e permette di scegliere l'`Executor` dentro il quale sarà eseguito l'_effect_. Inoltre, `lock` è _regionale_ (***regional***), ciò significa che:

1. quando un _effect_ viene associato (_locked_) all'interno di un `Executor`, saranno associate tutte le sue parti;
2. gli _scope_ interni hanno precedenza su quelli più esterni.

La seconda regola può essere illustrata mediante il seguente esempio:
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

Anche per quanto riguarda la concorrenza, ZIO offre diversi operatori che permettono di astrarre gli aspetti legati alle _fiber_, al fine di supportare il programmatore durante lo sviluppo di programmi concorrenti. I combinatori di base sono `raceEither` e `zipPar`, entrambi rappresentano delle operazioni binarie che permettono di combinare due valori:

- `zipPar`: descrive l'esecuzione di due _effects_ (`self` e `that`) in parallelo, l'attesa per il loro completamento e successivamente la restituzione dei risultati di entrambi; 
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

Il combinatore `zipPar` può essere utilizzato per implementare altri operatori tra cui `foreachPar` e `collectAllPar`, versione parallela di `foreach` e `collectAll`.

## Strutture concorrenti: Ref - Shared State

Molto spesso lo sviluppo di programmi concorrenti richiede la condivisione di uno stato tra le _fiber_, al fine di permettere uno scambio di informazioni. In ZIO questa necessità viene soddisfatta dalla struttura `Ref`, che può essere vista come un'alternativa puramente funzionale all'`AtomicReference`. Come nei casi precedenti, anche `Ref` descrive, e non effettua direttamente, una modifica di stato. In questo modo è possibile modellare programmi che rispettano la _trasparenza referenziale_ al fine di abilitare il programmatore a ragionare sull'applicativo mediante _substitution model_.
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
Ogni combinatore di `Ref` ritorna uno `ZIO` che descrive una modifica dello stato di `Ref` stesso. Un semplice esempio applicativo:
```scala
def increment(ref: Ref[Int]): UIO[Unit] =
  for {
    n <- ref.get
    _ <- ref.set(n + 1)
  } yield ()
```
Il metodo `increment` riceve un `Ref` in input e restituisce un _effect_ che ne descrive l'incremento.

`Ref` oltre ad essere un'alternativa puramente funzionale, si integra con la programmazione concorrente poiché l'accesso allo stato, da parte di più processi, avviene in maniera sicura. `Ref` ha però una limitazione, dalla prospettiva del chiamante, `update` legge sempre il vecchio valore e scrive quello nuovo in una singola "transazione" garantendo atomicità. Questo non è assicurato nel caso in cui `Ref` venga combinato con una serie di operatori. Per esempio, il comportamento `ref.get.flatMap(n => ref.set(n + 1))` è indeterminato perché molteplici _fiber_, eseguendo contemporaneamente, possono leggere lo stesso valore provocando la perdita di alcuni aggiornamenti. Delle buone pratiche da seguire per ovviare a questi tipi di problemi:

- inserire tutte le porzioni di uno stato che devono essere consistenti tra loro all'interno di una singola `Ref`;
- modificare una `Ref` sempre tramite una singola operazione;
- utilizzare sempre gli operatori `modify` e `update` al posto di `get` e `set` perché, essendo singole operazioni, garantiscono automaticamente l'atomicità.

Alternativamente, è possibile comporre operazioni in una `Ref` tramite la funzionalità **STM** (Software Transactional Memory) di ZIO.

Nel caso di `modify`, il parametro passatogli in input deve essere una funzione pura, cioè non deve produrre _side effects_, così che eseguendo l'operazione una sola volta, oppure tante, porterebbe sempre allo stesso stato. Nel caso si volesse passare a `modify` una `f` che produce _side effects_, si deve ricorrere al tipo di dato `Ref.Synchronized`.  
```scala
trait Synchronized[A] {
  def modifyZIO[R, E, B](f: A => ZIO[R, E, (B, A)]): ZIO[R, E, B]
}
```
Internamente `Synchronized` è implementato tramite un semaforo (`Semaphore`) così da garantire che solo una _fiber_ alla volta possa interagire con la `Ref`.

### Stato locale alla _fiber_: FiberRef

A volte, può essere necessario mantenere uno stato interno ad ogni _fiber_, in ZIO è possibile definendo un `FiberRef`. Questo, oltre a comporsi di un valore iniziale come in `Ref`, definisce altre due operazioni:
 
- `fork`: descrive l'eventuale processo di modifica del valore `ref` della _fiber_ padre, che deve essere copiato nel figlio;
- `join`: descrive come il valore della `ref` del padre sarà combinata con quella del figlio una volta terminato.
```scala
def make[A](
  initial: A,
  fork: A => A,
  join: (A, A) => A
): UIO[FiberRef[A]] =
  ???
```
Un programma esemplificativo può essere la realizzazione di un _logger_ che permette di visualizzare la struttura di come le computazioni vengono parallelizzate (`fork`) e riunite (`join`).
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

`Ref` consente di condividere lo stato tra diverse _fiber_, ma non può essere utilizzato come meccanismo di sincronizzazione. Per ovviare a questa mancanza, ZIO mette a disposizione un'altra struttura concorrente chiamata `Promise`, di seguito viene proposta una versione semplificata della sua interfaccia.
```scala
trait Promise[E, A] {
  def await: IO[E, A]
  def fail(e: E): UIO[Boolean]
  def succeed(a: A): UIO[Boolean]
}
```

Una `Promise` può essere vista come un contenitore, pieno o vuoto, che racchiude una _failure_ `E` oppure un valore `A`, inoltre può essere completata, tramite `fail` o `succeed`, solamente una volta. Il risultato di una `Promise` può essere recuperato mediante l'operatore "bloccante" `await`.  

Un semplice utilizzo della `Promise` ai fini di sincronizzazione:
```scala
for {
  promise <- Promise.make[Nothing, Unit]
  left    <- (Console.print("Hello, ") *> promise.succeed(())).fork
  right   <- (promise.await *> Console.print("World!")).fork
  _       <- left.join *> right.join
} yield ()
```
Il programma proposto è deterministico, cioè la stampa a video sarà sempre `"Hello World"`. Quindi si può dire che la `Promise` permette di imporre un'ordinamento lineare tra porzioni di codice concorrente.

### Completare una _Promise_

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

### Attendere una _Promise_

ZIO offre diversi metodi che permetto di osservare lo stato di una `Promise`:

- `await`: sospende la _fiber_ corrente fino al completamento della `Promise`, e ne ritorna l'_effect_;
- `isDone`: permette di controllare se la `Promise` è stata completata;
- `poll`: ritorna un `Option[IO[E, A]]` che varrà `None`, se la `Promise` non è stata ancora completata, oppure `Some` se l'_effect_ è pronto per essere restituito.
```scala
trait Promise[E, A] {
  def await: IO[E, A]
  def isDone: UIO[Boolean]
  def poll: UIO[Option[IO[E, A]]]
}
```

### Interrompere una _Promise_

Le _Promises_ supportano il modello di interruzione di ZIO. Ciò significa che il completamento di una `Promise` con un _effect_ interrotto, causerà l'interruzione di tutte le _fiber_ in attesa su quella `Promise`. 

Una `Promise` può essere interrotta anche manualmente tramite le funzioni `interrupt` e `interruptAs`.
```scala
trait Promise[E, A] {
  def interrupt: UIO[Boolean]
  def interruptAs(fiberId: FiberId): UIO[Boolean]
}
```
Tramite il parametro `fiberId` di `interruptAs` è possibile specificare la _fiber_ che ha richiesto l'interruzione.

## Strutture concorrenti: Queue - Work distribution

Similmente ad una `Promise`, una `Queue` consente alle _fiber_ di sospendersi al fine di attendere l'inserimento o la richiesta di un valore della coda. A differenza della prima, la `Queue` permette di gestire molteplici valori, infatti il suo scopo principale è quello di distribuzione del lavoro tra le _fiber_. Ovviamente la struttura garantisce un accesso sicuro ai valori in caso di concorrenza.

Le operazioni fondamentali di una `Queue` sono `offer` e `take`:
```scala
trait Queue[A] {
  def offer(a: A): UIO[Boolean]
  def take: UIO[A]
}
```
La prima consente di inserire un valore nella coda, mentre `take` sospende (blocca semanticamente) la _fiber_ corrente finché non è presente un valore.

### Varianti di _Queue_

Le due principali tipologie di code sono quella **`unbounded`**, che non ha una capacità massima, e quella **`bounded`** che definisce il numero massimo di valori che può contenere (_capacity_). Quest'ultima permette di prevenire problemi legati alla memoria (_memory leak problem_), ma richiede di definire la logica di gestione nel caso in cui si tenti di inserire un valore in una coda piena. 

A tal proposito, ZIO mette a disposizione tre strategie:

- ***Back Pressure***: sospende il chiamante del metodo `offer` finché la coda è satura. Questa strategia evita la perdita di informazioni, ma obbliga i _consumers_ ad elaborare tutti i valori prima di raggiungere i più recenti, quindi non è la scelta ideale per un'applicazione finanziaria.
  ```scala
  for {
    queue <- Queue.bounded[String](2)
    _ <- queue.offer("ping")
    .tap(_ => Console.printLine("ping"))
    .forever
    .fork
  } yield ()
  ```
- ***Sliding Strategy***: in questo caso, l'inserimento di un valore in una coda già piena provocherà la rimozione del primo elemento della `Queue`. Questa strategia deve essere applicata quando i valori più recenti hanno un valore superiore rispetto a quelli precedentemente aggiunti. Per realizzare una coda scorrevole ci si avvale del costruttore `sliding`.
  ```scala
  for {
    queue <- Queue.sliding[Int](2)
    _ <- ZIO.foreach(List(1, 2, 3))(queue.offer)
    a <- queue.take
    b <- queue.take
  } yield (a, b)
  ``` 
  In questo esempio i valori ritornati saranno sempre gli ultimi due (`2` e `3`).
- ***Dropping Strategy***: la richiesta di inserimento verrà scartata nel caso in cui la coda abbia raggiunto la capacità massima. Questa strategia ha senso quando si vuole mantenere una distribuzione dei valori piuttosto che avere quelli più recente, come nel caso dei dati meteorologici. Questa tipologia di coda può essere realizzata tramite il costruttore `dropping`.
  ```scala
  for {
    queue <- Queue.dropping[Int](2)
    _ <- ZIO.foreach(List(1, 2, 3))(queue.offer)
    a <- queue.take
    b <- queue.take
  } yield (a, b)
  ```

### Chiusura di una _Queue_

Siccome potrebbero esserci più _fiber_ in attesa di inserire o prelevare dei valori da una coda, si vuole avere un modo per interrompere quelle _fiber_ nel caso in cui la `Queue` non fosse più utile. Il metodo `shutdown` fornisce proprio quello strumento.
```scala
trait Queue[A] {
  def awaitShutDown: UIO[Unit]
  def isShutdown: UIO[Boolean]
  def shutdown: UIO[Unit]
}
```
Gli altri due metodi consentono di interrogare la coda al fine di verificarne la chiusura (`isShutdown`), e di sospendersi finché la coda rimane aperta (`awaitShutDown`).

## Strutture concorrenti: Hub - Broadcasting

Una classe di problema meno diffusa rispetto a quella di distribuzione del lavoro, è il _broadcasting_: ogni valore comunicato deve essere ricevuto da ogni _consumer_. La soluzione di ZIO a tale problema, è la struttura dati `Hub`, la quale assegna ad ogni _consumer_ uno specifico indice, così che i _consumer_ accedano a posizioni distinte della struttura. Un `Hub` è definito in termini di due operazioni fondamentali:

- `publish`: similmente a a quanto succede con il metodo `offer` di `Queue`, il metodo consente di pubblicare un valore all'interno della struttura;
- `subscribe`: permette di sottoscriversi per ottenere i valori dal `Hub` sotto forma di `Dequeue`. I _consumer_ continueranno a ricevere valori fintanto che sono sottoscritti.
```scala
sealed abstract class Hub[A] {
  def publish(a: A): UIO[Boolean] = ???
  def subscribe: ZIO[Scope, Nothing, Dequeue[A]] = ???
}
richiede un valore assegnato in un certo ind
object Hub {
  def bounded[A](capacity: Int): UIO[Hub[A]] = ???
}
```
Concettualmente, ogni _consumer_ interagisce con la struttura come se fosse un canale le cui estremità vengono rispettivamente rappresentate dalle interfacce `Enqueue` e `Dequeue`.

### Varianti di _Hub_

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

Un'altra variante di `Hub` è quello di tipo ***sliding*** che, come si evince dal nome, implementa la strategia _sliding_ al fine di liberare spazio eliminando i dati meno recenti. Questa tipologia garantisce il completamento immediato dell'attività di pubblicazione (_publishing_).
```scala
object Hub {
  def sliding[A](requestedCapacity: Int): UIO[Hub[A]] =
    ???
}
``` 

Infine, l'ultima variante è quella di tipo `Dropping`, in cui la pubblicazione di un valore nel caso di spazio indisponibile, provoca il fallimento dell'operazione. Il fallimento o il successo di questa viene segnalato tramite un valore `Boolean` ritornato dal metodo `publish`.
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
Proprio come una `Queue`, anche l'`Hub` può essere chiuso (`shutdown`) causando l'interruzione immediata di qualsiasi _fiber_ che tenterà di inserire o ricevere tramite sottoscrizione un valore dalla struttura. Inoltre è possibile conoscere il numero di messaggi presenti all'interno dell'`Hub` tramite l'operatore `size`. Infine la funzione `toQueue` consente di interagire con la struttura come se fosse una coda su cui è possibile solo scrivere. 


## Strutture concorrenti: Semaphore - Work limiting

Un `Semaphore` viene creato con un certo numero di "permessi" ed è definito attraverso un unico metodo `withPermits`.
```scala
trait Semaphore {
  def withPermits[R, E, A](n: Long)
    (task: ZIO[R, E, A]): ZIO[R, E, A]
}
```
Ogni _fiber_ che chiama _withPermits_ deve prima "acquisire" il numero di permessi specificato all'atto della creazione. Se i permessi sono disponibili allora questi vengono decrementati e la _fiber_ può procedere con l'esecuzione dell'attività. In caso contrario, la _fiber_ si sospenderà finché non ci saranno permessi disponibili. 

La struttura `Semaphore` permette quindi di limitare il grado di concorrenza, cioè il numero massimo di _fiber_ che accedo contemporaneamente a un certo blocco di codice, evitando modifiche del programma. 

Un caso d'uso comune del `Semaphore` è la creazione di strutture dati _concurrently safe_. L'idea di base è quella di implementare un `Semaphore` con un solo permesso, così che il blocco di codice protetto possa essere eseguito da una solo _fiber_ alla volta. In questo modo un `Semaphore` agisce come una _lock_ o un _synchronized block_ della programmazione concorrente tradizionale, evitando però di bloccare i _thread_ di sistema. Un esempio applicativo può essere la creazione di una `Ref` protetta da un semaforo.
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
Come mostrato nell'esempio, l'implementazione del metodo `modify` della `Ref` è racchiuso all'interno di `withPermit`. In questo modo se due _fiber_ tentano di modificare in maniera concorrente la `Ref`, la prima otterrà il valore, eseguirà l'_effect e aggiornerà lo stato, mentre la seconda sarà sospesa a causa di un numero insufficiente di permessi.


