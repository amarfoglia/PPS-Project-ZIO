# Gestione delle risorse

L'accesso ad una risorsa, ad esempio un file o una _socket_, deve obbligatoriamente includere anche la fase di rilascio. Questa non sempre viene eseguita, sopratutto in contesti asincroni e concorrenti, causando un "blocco" ingiustificato della risorsa. A tal fine `ZIO` propone di accedere alle risorse sfruttando gli strumenti `acquireRelease` e `Scope`, poiché ne garantiscono il rilascio automatico. 

La soluzione tradizionale al problema della gestione delle risorse è il costrutto `try ... finally`, che assicura il loro rilascio anche a fronte di eccezioni.
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
Questa tecnica è valida nel contesto sincrono, ma presenta delle falle in quello asincrono/concorrente, poiché il codice può essere interrotto in qualsiasi punto. 

## Acquire & Release - Gestione sicura delle risorse

La funzione `acquireReleaseWith` permette di gestire tutte le fasi del ciclo di vita di una risorsa in unico punto, così che non si verifichino stati incoerenti. 

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
Il parametro `acquire` descrive l'acquisizione di una certa risorsa `A`, mentre `use` definisce la produzione di un certo risultato `B` a partire dalla risorsa `A` acquisita. Infine `release` consente il rilascio della risorsa, e come si evince dalla firma, non prevede fallimenti.

L'operatore `acquireReleaseWith` fornisce le seguenti garanzie:

- l'azione di acquisizione (`acquire`) sarà eseguita senza interruzioni;
- l'azione di rilascio (`release`) sarà eseguita senza interruzioni;
- l'azione di rilascio verrà eseguita sempre, indipendentemente dal risultato dell'utilizzo (`use`).

Tramite la variante `acquireReleaseExitWith` è possibile ottenere informazioni, relative allo stato di terminazione di `use`, sotto forma di un risultato di tipo `Exit[E, B]`.

Infine, `ZIO` espone anche l'operatore `ensuring`, il quale consente di applicare una certa azione di finalizzazione ad un _workflow_ di _effect_, garantendone l'esecuzione anche in caso di interruzione o fallimento. 
```scala
trait ZIO[-R, +E, +A] {
  def ensuring[R1 <: R](finalizer: ZIO[R1, Nothing, Any]):
    ZIO[R1, E, A]
}
```
La funzione `acquireReleaseWith` è preferibile a `ensuring` nel caso in cui sia richiesta un'operazione di _allocazione_ in aggiunta a quella di _deallocazione_.  

### Scope - Risorse componibili

Un limite di `acquireReleaseWith` è la sua scarsa ergonomia nel caso di composizione di molteplici risorse. Si prenda come esempio un programma che richiede l'accesso a due _file_ di testo, con l'intento di elaborarne il contenuto all'interno di un'unica operazione (`analyzeData`). 
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
La soluzione proposta è corretta ma impone un ordine sulla gestione delle risorse che rende impossibile una loro acquisizione in parallelo impattando negativamente sulle prestazioni. Inoltre nel caso di numerose acquisizioni si andrebbero a creare tanti livelli innestati, difficilmente gestibili.

I problemi appeni citati possono essere evitati adottando un approccio più dichiarativo, che consenta di combinare le risorse creandone una nuova, la quale descriva l'acquisizione e il rilascio di entrambe. La funzione di ZIO `acquireRelease` permette di fare ciò separando le operazioni di rilascio e acquisizione, dalla logica di utilizzo della risorsa. 
```scala
def acquireRelease[R, E, A](
  acquire: ZIO[R, E, A]
)(release: A => ZIO[R, Nothing, Any]): ZIO[R with Scope, E, A] =
  ???
```
`Scope` rappresenta un oggetto al quale è possibile agganciare delle azioni di finalizzazione, eseguite alla sua chiusura indipendentemente da fallimenti o interruzioni. Uno `Scope` può essere creato avvalendosi del costruttore `scoped`. 

Dati questi strumenti è possibile riscrivere il programma precedente nel seguente modo:
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
La funzione `file` consente di descrivere la risorsa tralasciando la logica di utilizzo. Grazie a `Scope` è possibile interagire con le risorse come se fossero dei valori di `ZIO`, quindi componibili tramite i classici operatori della libreria tra cui: `flatMap`, `map` e `zipPar`.  Inoltre, il blocco `ZIO.scoped` circoscrive la durata di vita della risorsa, quindi questa non può essere utilizzata esternamente a meno che il suo contenuto non venga copiato/convertito in qualcos'altro. Per esempio nel caso di un file, il testo può essere caricato in memoria. 

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
Nell'esempio proposto viene garantita la terminazione del `heartbeat` alla chiusura dello `Scope`. Questo offre al chiamante un maggior controllo sul processo e permette di estrarre dal metodo il concetto di durata. Inoltre, l'operatore `forkScoped` esegue la _fiber_ all'interno dello `Scope` specificato.
