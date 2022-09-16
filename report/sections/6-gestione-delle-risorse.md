# Gestione delle risorse

Ogni volta che si accede ad una risorsa, ad esempio un file o una _socket, è fondamentale rilasciare questa una volta utilizzata. Può capitare però di dimenticarsi, sopratutto in contesti asincroni e concorrenti, a tal fine ZIO fornisce `acquireRelease` e `Scope` come strumenti che garantiscono il rilascio automatico delle risorse acquisite. 

La soluzione tradizionale al problema della gestione sicura delle risorse è il costrutto `try ... finally`, che assicura il rilascio delle risorse anche nel caso di eccezioni.
```scala
lazy val example = {
  val resource = acquire
  try {
    use(resource)
  } finally {
    release(resource)
  }
}
```
Questa tecnica è valida in un contesto sincrono, ma presenta delle falle se si introduce l'asincronia e la concorrenza, perché il codice può essere interrotto in qualsiasi punto. 

## Acquire & Release - Gestione sicura delle risorse

Tutte le fasi del ciclo di vita di una risorsa devono essere gestite complessivamente, e la funzione `acquireReleaseWith` fa proprio questo.
```scala
import zio._
object ZIO {
  def acquireReleaseWith[R, E, A, B](
    acquire: ZIO[R, E, A]
  )(
    release: A => ZIO[R, Nothing, Any]
  )(use: A => ZIO[R, E, B]): ZIO[R, E, B] =
    ???
}
```
Il parametro `acquire` descrive l'acquisizione di una certa risorsa `A`. La lambda `use` definisce la produzione di un certo risultato `B` a partire dalla risorsa `A` acquisita.
Mentre `release` consente il rilascio della risorsa, e come si evince dalla firma, non prevede fallimenti.

L'operatore `acquireReleaseWith` fornisce le seguenti garanzie:

- l'azione di acquisizione (`acquire`) sarà eseguita senza interruzioni;
- l'azione di rilascio (`release`) sarà eseguita senza interruzioni;
- l'azione di rilascio verrà eseguita sempre, indipendentemente dal risultato dell'utilizzo (`use`).

Per riceve informazioni relative alla modalità di completamento di `use`, è possibile sfruttare la variante `acquireReleaseExitWith` che incapsula queste all'interno di un risultato di tipo `Exit[E, B]`. 

Infine, ZIO espone anche l'operatore `ensuring` che consente di applicare una certa azione di finalizzazione ad un _workflow_ di _effect_, garantendone l'esecuzione anche in caso di interruzione o fallimento. 
```scala
trait ZIO[-R, +E, +A] {
  def ensuring[R1 <: R](finalizer: ZIO[R1, Nothing, Any]):
    ZIO[R1, E, A]
}
```
Se si sta lavorando con una risorsa, o con qualsiasi cosa che richieda "allocazione" oltre alla "deallocazione", si deve utilizzare `acquireReleaseWith`, altrimenti `ensuring`.

### Scope - Risorse componibili

Un limite di `acquireReleaseWith` è la sua scarsa ergonomia nel caso in cui si voglia comporre molteplici risorse. Si prenda come esempio un programma che richiede l'accesso a due file di testo, con l'intento di elaborarne i dati all'interno di un'unica operazione (`analyzeData`). 
```scala
def openFile(name: String): IO[IOException, File] = ???
def closeFile(file: File): UIO[Unit] = ???
def analyze(weatherData: File, results: File): Task[Unit] = ???
def withFile[A](name: String)(use: File => Task[A]): Task[A] =
  ZIO.acquireReleaseWith(openFile(name))(closeFile)(use)

lazy val analyzeData: Task[Unit] =
  withFile("file1.txt") { data1 =>
    withFile("file2.txt") { data2 =>
      analyze(data1, data2)
    }
  }
```
La soluzione proposta è corretta ma nel caso di più acquisizioni il numero di livelli innestati diventerebbe ingestibile, inoltre si sta imponendo un ordine sulla gestione dei file. Questo inficia sull'efficienza del programma, poiché si rende impossibile il caricamento delle risorse in parallelo. 

I problemi appeni citati possono essere evitati riconducendo il programma ad un approccio più dichiarativo, che consenta di combinare le risorse creandone una nuova, la quale descriva l'acquisizione e il rilascio di entrambe. La funzione di ZIO `acquireRelease` permette di fare ciò separando le operazioni di rilascio e acquisizione, dalla logica di utilizzo della risorsa. 
```scala
def acquireRelease[R, E, A](
  acquire: ZIO[R, E, A]
)(release: A => ZIO[R, Nothing, Any]): ZIO[R with Scope, E, A] =
  ???
```
`Scope` rappresenta un oggetto al quale è possibile agganciare delle azioni di finalizzazione, eseguite alla sua chiusura. Tramite il costruttore `scoped` è possibile creare un nuovo `Scope` che completerà la sua esecuzione anche in caso di fallimento o interruzione. Dati questi strumenti è possibile riscrivere il programma precedente nel seguente modo:
```scala
def file(name: String): ZIO[Scope, Throwable, File] =
  ZIO.acquireRelease(openFile(name))(closeFile)

lazy val parallelAcquire: ZIO[Scope, Throwable, (File, File)] =
  file("file1.txt").zipPar(file("file2.txt"))

def analyzeData(
  files: ZIO[Any, Nothing, (File, File)]
): Task[Unit] =
  ZIO.scoped {
    files.flatMap { case (data1, data2) =>
      analyze(data1, data2)
    }
  }
```
La funzione `file` consente di descrivere la risorsa indipendentemente da come verrà sfruttata. L'utilizzo di `Scope` permette di interagire con le risorse come se fossero dei semplici valori di ZIO, quindi possono essere composti sfruttando gli operatori della libreria, come: `flatMap`, `map` e `zipPar`.  Inoltre, `ZIO.scoped` definisce la durata di vita della risorsa indipendentemente dalla sua creazione. Quindi la risorsa per essere utilizzata dall'esterno deve prima essere convertita in qualcos'altro, per esempio nel caso di un file, il testo può essere caricato in memoria. 

Tramite `Scope` è possibile modellare esplicitamente il tempo di vita di una _fiber_, continuando ad utilizzare gli operatori precedentemente presentati. Per esempio, si potrebbe definire un programma che in _background_ invia un `heartbeat`:
```scala
val heartbeat: ZIO[Scope, Nothing, Fiber[Nothing, Unit]] =
  Console.printLine(".").orDie.delay(1.second).forever.forkScoped

lazy val myProgramLogic: ZIO[Any, Nothing, Unit] =
  ???

ZIO.scoped {
  for {
    _ <- heartbeat
    _ <- myProgramLogic
  } yield ()
}
```
Nell'esempio proposto viene garantita la terminazione del `heartbeat` alla chiusura dello `Scope`. Questo permette di delegare la definizione della durata del segnale al chiamante, evitando il suo inserimento all'interno della logica della funzione. La _fiber_ viene eseguita all'interno dello `Scope` specificato, grazie all'operatore `forkScoped`.
