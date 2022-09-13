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

## Operatori per la concorrenza

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


