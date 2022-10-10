# STM - Software Transactional Memory

STM è uno strumento che permette di comporre singole operazioni in un'unica transazione eseguibile in maniera atomica. Questo consente di risolvere agilmente le sfide e i problemi della programmazione concorrente; il codice scritto con STM non è affetto da _deadlocks_. Inoltre, seguendo la logica di `ZIO`, anche per gli aggiornamenti atomici è necessario fornire una loro descrizione così da ritardare la loro valutazione. Questo permette di costruire transazioni sempre più complesse sfruttando un approccio incrementale. Nella pratica ci si avvale del tipo di dato `ZSTM`.

`ZSTM[R, E, A]` descrive una transazione che richiede un _environment_ `R` e che può fallire con un errore `E`, oppure avere successo con un valore `A`. Nel caso in cui l'ambiente non fosse necessario si può utilizzare l'alias `STM`.
```scala
type STM[+E, +A] = ZSTM[Any, E, A]
```
L'implementazione di STM in `ZIO` si basa sul tipo di dato `TRef`, il quale è equivalente a `Ref` fatta eccezione per gli operatori che, in questo caso, restituiscono sempre _effect_ STM automaticamente componibili. 
```scala
trait TRef[A] {
  def get: STM[Nothing, A]
  def modify[B](f: A => (B, A)): STM[Nothing, B]
  def set(a: A): STM[Nothing, Unit]
  def update(f: A => A): STM[Nothing, Unit]
}
```
L'esecuzione di una transazione STM comprende i seguenti passaggi:

1. si esegue provvisoriamente la transazione memorizzando i risultati delle variabili transazionali;
2. si verifica se i valori delle variabili transazionali sono stati modificati da quando si è avviata la transazione;
3. se i valori non sono cambiati la transazione viene applicata;
4. se i valori hanno subito delle modifiche, la transazione fallisce, e si riparte dal primo punto.

Si prenda come esempio il trasferimento di fondi tra due conti bancari.
```scala
def transfer(
  from: TRef[Int],
  to: TRef[Int],
  amount: Int
): STM[Throwable, Unit] =
  for {
    senderBalance <- from.get
    _             <- if (amount > senderBalance)
                      STM.fail(new Throwable("insufficient funds"))
                    else
                      from.update(_ - amount) *>
                        to.update(_ + amount)
  } yield ()
```
La logica è corretta perché se la variabile transazionale `from` venisse modificata, la transazione fallirebbe causando il ripristino delle variabili ai valori originali, e l'intera transazione verrebbe rieseguita automaticamente.

In alcuni casi potrebbe essere necessario astrarre dalla logica STM, a tal proposito `ZIO` fornisce l'operatore `commit`: converte una transazione in un _effect_. Sfruttando la funzione `commit` è possibile ristrutturare il codice precedente in modo da migliorarne la leggibilità lato chiamante.
```scala
final class Balance private (
  private val value: TRef[Int]
) { self =>
  def transfer(that: Balance, amount: Int): Task[Unit] = {
    val transaction: STM[Throwable, Unit] = for {
      senderBalance <- value.get
      _             <- if (amount > senderBalance)
                        STM.fail(
                          new Throwable("insufficient funds")
                        )
                       else
                          self.value.update(_ - amount) *>
                            that.value.update(_ + amount)
    } yield ()
    transaction.commit
  }
}
```

## STM - Operatori

Generalmente, quasi tutti gli operatori di `ZIO` hanno una controparte in ZSTM, fatta eccezione per quelli che si occupano di concorrenza e _effect_ arbitrari. Oltre ai classici operatori come `flatMap`, `map`, `foldSTM`, `zipWith` e `foreach`, se ne aggiungo altri, specifici di STM, di cui il più importante è `retry`: fa sì che un'intera transazione venga rieseguita.
```scala
def autoDebit(
  account: TRef[Int],
  amount: Int
): STM[Nothing, Unit] =
  account.get.flatMap { balance =>
    if (balance >= amount) account.update(_ - amount)
    else STM.retry
}
```
Senza la funzione `retry` le transazioni verrebbero rieseguite solo a fronte di un cambiamento delle variabili transazionali, rendendo impossibile la definizione di una logica di ripristino controllata. Nell'esempio, la transazione verrà ripetuta finché non completa con successo.

## STM - Limitazioni

STM non supporta _effect_ arbitrari all'interno delle transazioni. Si consideri l'esempio seguente:
```scala
val arbitraryEffect: UIO[Unit] =
  ZIO.succeed(println("Running"))
```
In questo caso, il tentativo di esecuzione della transazione visualizzerà su _console_ il messaggio `"Running"`. Ad ogni suo fallimento verrà riproposta la stessa stampa, provocando una differenza osservabile tra il tentativo iniziale e quelli successivi. Questo non consente di ragionare sulla logica delle transazioni senza conoscere a priori il numero di tentativi. Di conseguenza, non è possibile svolgere istruzioni concorrenti all'interno di una transazione STM, però le transazioni possono essere eseguite in maniera concorrente.
```scala
for {
  alice    <- TRef.make(100).commit
  bob      <- TRef.make(100).commit
  carol    <- TRef.make(100).commit
  transfer1 = transfer(alice, bob, 30).commit
  transfer2 = transfer(bob, carol, 40).commit
  transfer3 = transfer(carol, alice, 50).commit
  transfers = List(transfer1, transfer2, transfer3)
  _        <- ZIO.collectAllParDiscard(transfers)
} yield ()
```

Il limite appena proposto può essere superato nel caso di:

- _effect idempotenti_[^8]: si prenda una `Promise[Int]`, la chiamata `promise.succeed(42).ignore` avrà lo stesso effetto indipendentemente dal numero di esecuzioni;
- _effect_ che hanno una funzione _inversa_: gli effetti prodotti da una _query_ di inserimento possono essere annullati tramite un _effect_ che implementa la rimozione.

Infine il secondo limite delle transazioni STM, è relativo alle _performance_. In una situazione di forte contesa, cioè con elevati conflitti di aggiornamento, le transazioni devono essere ripetute svariate volte provocando una riduzione delle _performance_. In questo caso, è consigliato adottare delle strutture concorrenti come `Queue`, oppure utilizzare un `Semaphore` per proteggere gli accessi.

[^8]: eseguire un _effect_ una sola volta è equivalente a farlo più volte.

## STM - Strutture dati

Le strutture dati di STM sono definite in termini di uno o più valori `TRef`, quindi rappresentano concetti mutabili in grado di partecipare alle transazioni. Quando si opera su queste strutture dati, le modifiche vengono applicate sulla struttura stessa, quindi non vi è la creazione di un nuovo dato. 

### TArray

Un `TArray` è la versione STM di un _array_ mutabile, in cui la dimensione definita alla creazione non può essere cambiata e il valore di ogni indice è rappresentato da un `TRef` distinto.
```scala
final class TArray[A] private (private val array: Array[TRef[A]]
```
La funzione del `TArray`, in un contesto transazionale, è identica a quella di un classico `Array`, ed è la scelta ottimale in caso di letture e scritture veloci ad accesso casuale.

La creazione di un `TArray` avviene tramite il costruttore `make`, e le funzioni fondamentali sono `apply` e `update`. La prima consente di accedere al valore di un certo indice, mentre la seconda permette di aggiornarlo.

Un esempio applicativo di `TArray`, può essere l'implementazione di un operatore `swap` che scambia gli elementi di due indici in una singola transazione.
```scala
def swap[A](
  array: TArray[A],
  i: Int,
  j: Int
): STM[Nothing, Unit] =
  for {
    a1 <- array(i)
    a2 <- array(j)
    _  <- array.update(i, _ => a2)
    _  <- array.update(j, _ => a1)
  } yield ()
```
Nel caso di coppie di elementi con indici differenti, siccome per ogni posizione c'è un `TRef` a sé stante, è possibile eseguire due _fiber_ parallelamente evitando conflitti e conseguenti `retry`. 

Un `TArray` supporta operatori come: `collectFirst`, `contains`, `count`, `exists`, `find`, `fold`, `forall`, `maxOption`, e `minOption`. Tra questi non è presente `map` poiché non è allineato con la logica di mutabilità delle strutture dati STM. Questo viene sostituito da `transform` che permette di applicare le modifiche "sul posto".
```scala
val transaction = for {
  array <- TArray.make(1, 2, 3)
  _     <- array.transform(_ + 1)
  list  <- array.toList
} yield list
```

### TMap

Una `TMap` è una _map_ mutabile che può essere utilizzata all'interno di un contesto transazionale. Trova largo impiego quando si vuole accedere ai valori tramite chiave, e si vuole cambiare dinamicamente la dimensione della struttura dati tramite aggiunta o rimozione.

Gli operatori fondamentali di `TMap` sono `delete`, `get`, e `put`.
```scala
trait TMap[K, V] {
  def delete(k: K): STM[Nothing, Unit]
  def get(k: K): STM[Nothing, Option[V]]
  def put(k: K, v: V): STM[Nothing, Unit]
}
```
A questi se ne aggiungono altri più classici come: `contains`, `isEmpty`, `fold`, `foreach`, `keys`.

### TPriorityQueue

Una `TPriorityQueue` è equivalente a una classica coda, fatta eccezione per la politica di gestione dei valori in uscita, che viene definita dal programmatore tramite `Ordering`. Per costruire una `TPriorityQueue` ci si avvale di `make`, `fromIterable` oppure `empty`.

`TPriorityQueue` può essere estremamente utile quando si vuole rappresentare una coda in grado di supportare inserimenti (`offer`) e prelievi (`take`) simultanei e in cui si desidera sempre prendere il valore "più piccolo" o "più grande".

Uno scenario d'uso può essere una coda di eventi, i cui elementi sono associati a un _timestamp_ utilizzato per implementare la politica di ordinamento. 
```scala
final case class Event(time: Int, action: UIO[Unit])
object Event {
  implicit val EventOrdering: Ordering[Event] =
    Ordering.by(_.time)
}

for {
  queue <- TPriorityQueue.empty[Event]
} yield queue
```

Una `PriorityQueue` supporta le tipiche operazioni che ci si aspetta da una coda: `offer`, `offerAll`, `take`, `takeAll`, `takeUpTo`, `peek` e `size`.

### TPromise

Lo scopo di `TPromise` è equivalente a quello delle `Promise` ma calato in un contesto transazionale. In altre parole, `TPromise` permette di sincronizzare il lavoro di _fiber_ differenti eseguite su transazioni distinte. 
```scala
final class TPromise[E, A] private (
  private val ref: TRef[Option[Either[E, A]]]
)
```

Una `TPromise` è semplicemente una `TRef` il cui contenuto può essere vuoto, oppure valorizzato con un valore `A` in caso di completamento, oppure un errore `E` a fronte di un fallimento. Le operazioni fondamentali sono `await`, `done` e `poll`.

### TQueue

La struttura dati `TQueue` è simile a quella di `TPriorityQueue`, precedentemente presentata, fatta eccezione per la politica di gestione degli elementi, che in questo caso è FIFO (_first in first out_).
```scala
final class TQueue[A] private (
  private val capacity: Int,
  private val ref: TRef[ScalaQueue[A]]
)
```
La struttura viene implementata con una singola `TRef` perché l'aggiunta o la rimozione degli elementi comporta un'intera modifica della coda, quindi non vi sono casi in cui si effettuano modifiche separate su parti differenti.

### TReentrantLock

`TReentrantLock` è equivalente a quello di Java, e supporta quattro metodi di base.
```scala
trait TReentrantLock {
  val acquireRead: STM[Nothing, Int]
  val acquireWrite: STM[Nothing, Int]
  val releaseRead: STM[Nothing, Int]
  val releaseWrite: STM[Nothing, Int]

  val readLock: ZIO[Scope, Nothing, Int] =
    ZIO.acquireRelease(acquireRead.commit)(_ => releaseRead.commit)
  val writeLock: ZIO[Scope, Nothing, Int] =
    ZIO.acquireRelease(acquireRead.commit)(_ => releaseRead.commit)
}
```
La struttura dati in questione può essere equiparata a un `Semaphore` in cui però vi è una netta separazione tra il concetto di accesso in lettura e quello in scrittura. Questa distinzione tra _lettori_ e _scrittori_ è fondamentale perché permette a più _fiber_ di eseguire parallelamente fintanto che nessuna effettua modifiche ("scritture").

Un `TReentrantLock` si basa su questa idea consentendo a un numero illimitato di _fiber_ di acquisire contemporaneamente un _lock_ in lettura, e limitando l'accesso in scrittura ad una sola _fiber_ per volta. Quindi l'obiettivo della `TReentrantLock` è quello di prevenire la modifica di una risorsa da parte di molteplici _fiber_.

Per mettere in pratica i concetti appena presentati, si prenda come esempio un sistema ad attori, composto da due supervisori, in cui si vuole descrivere il trasferimento di un attore da un supervisore a l'altro. 
```scala
trait Actor

trait Supervisor {
  def lock: TReentrantLock
  def supervise(actor: Actor): UIO[Unit]
  def unsupervise(actor: Actor): UIO[Unit]
}

object Supervisor {
  def transfer(
    from: Supervisor,
    to: Supervisor,
    actor: Actor
  ): UIO[Unit] = {
    ZIO.acquireReleaseWith {
      (from.lock.acquireWrite *> to.lock.acquireWrite).commit
    } { _ =>
      (from.lock.releaseWrite *> to.lock.releaseWrite).commit
    } { _ =>
      from.unsupervise(actor) *> to.supervise(actor)
    }
  }
}
```
Attraverso il metodo `transfer` è possibile richiedere agilmente il _lock_ di due oggetti con stato mutabile, garantendo l'assenza di _race conditions_ e _deadlock_.

### TSemaphore

Come si evince dal nome, un `TSemaphore` è la variante del tipo di dato `Semaphore` valida nel contesto transazionale. Tramite `TSemaphore` è possibile realizzare una politica di controllo con la quale comporre altri _effect_ transazionali. Di seguito viene proposto un esempio in cui l'acquisizione e il rilascio del permesso fanno parte della stessa transazione. 
```scala
import zio._
import zio.stm._

val tSemaphoreWithPermit: IO[Nothing, Unit] =
  for {
    sem <- TSemaphore.make(1L).commit
    a   <- sem.withPermit(yourSTMAction.commit)
  } yield a
```

Un `TSemaphore` altro non è che un `TRef` che racchiude un valore `Long` che indica il numero di permessi disponibili. Similmente a quanto accade per un semaforo di ZIO, per acquisire un permesso si deve prima verificare che il valore del contatore sia maggiore di zero, e nel caso lo si decrementa di uno. Il rilascio di un permesso invece, implica l'incremento del contatore.

### TSet

Un `TSet` corrisponde a un classico _set_ mutabile ma in grado di partecipare ad una transazione STM. Internamente è implementato come una `TMap` definita da solo le chiavi.
```scala
final class TSet[A] private(private val map: TMap[A, Unit])
```
`TSet` può essere creato sfruttando i costruttori `make`, `fromIterable` ed `empty`, e supporta gli operatori che normalmente vengono utilizzati in un _set_, tra questi: `put`, `delete`, `diff`, `intersect`, e `union`.
