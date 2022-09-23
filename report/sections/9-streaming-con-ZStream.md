# Streaming con ZStream

Lo _streaming_ è un paradigma fondamentale per le moderne applicazioni basate sui dati. ZIO implementa tale paradigma attraverso il supporto `ZIO Stream`, cioè un'API di alto livello che astrae dai dettagli implementativi di basso livello. Un caso d'uso molto comune è quello in cui si ricevono costantemente nuove informazioni, necessarie per la produzione dell'output. Nella pratica, tramite un'approccio a _stream_, è possibile modellare una comunicazione tra due _socket_, oppure la lettura di un file, ma anche le richieste di un server http e la gestione degli eventi di un UI reattiva.

Il tipo di dato principale di `ZIO Stream` è `ZStream[R, E, O]`, cioè uno _stream_ che richiede un _environment_ `R` e che può fallire con un errore di tipo `E`, oppure completare correttamente con zero o più valori di tipo `O`. La differenza principale tra `ZIO` e `ZStream` è che il primo completa sempre con un solo valore, mentre il secondo può terminare con un numero di valori potenzialmente infinito. Questo è dovuto al fatto che `ZStream` produce i risultati in maniera incrementale. Come in `ZIO`, il tipo parametrico `E` rappresenta i possibili errori che si verificano in caso di fallimento. Questi possono essere gestiti tramite appositi operatori come `catchAll` e `catchSome`. 

Le caratteristiche di `ZStream` possono essere riassunte nei seguenti punti:

- dichiarativo e di alto livello: problemi complessi possono essere risolti agilmente con poche righe di codice;
- asincrono e non bloccante: gli `ZStream` risultano efficienti e altamente scalabili perché essendo reattivi e non bloccanti, evitano di sprecare risorse relative ai _thread_;
- concorrente e parallelizzabile: tutti gli operatori supportano la concorrenza e quelli paralleli permettono di saturare e utilizzare tutti i _core_ della CPU;
- gestione sicura delle risorse: una volta fornita la definizione di alto livello del diagramma di flusso, sarà `ZIO Stream` ad occuparsi della sua esecuzione, garantendo la corretta gestione delle risorse anche in caso di imprevisti o semplici interruzioni;
- performante ed efficiente: per raggiungere un elevato grado di efficienza, `ZStream` gestisce internamente le informazioni in _chunk_, fornendo un API fruibile a livello di singolo elemento.
- flusso di dati infinito: gli stream consentono di lavorare con un numero infinito di dati in una quantità finita di memoria. Questo è dovuto al fatto che si sta costruendo solo una descrizione dell'elaborazione.

`ZStream` è definito in termini di un solo operatore `process`, che può essere valutato ripetutamente per ricevere più elementi dallo _stream_. 
```scala
trait ZStream[-R, +E, +O] {
  def process: ZIO[R with Scope, Option[E], Chunk[O]]
}
```
`ZStream` può essere visto come la rappresentazione _funzionale_ di una collezione di valori potenzialmente infinita, che nella programmazione _imperativa_ corrisponde a un `Iterator`.

## Costruzione di Stream

Uno dei costruttori più semplici è `fromIterable`, che genera lo _stream_ a partire dai valori di un `Iterable`. 
```scala
lazy val stream: ZStream[Any, Nothing, Int] =
  ZStream.fromIterable(List(1, 2, 3, 4, 5))
```
In questo caso gli _stream_ prodotti sono piuttosto banali perché forniscono un set di valori finito e non coinvolgono _effect_. 

Un altro costruttore interessante è `fromZIO`, il quale permette di definire _stream_ con un solo elemento. Quest'ultimo però è un _effect_, che gestito all'interno del contesto dello _stream_ può essere combinato con altri. 
```scala
val helloStream: ZStream[Any, Nothing, Unit] =
  ZStream.fromZIO(Console.printLine("Hello").orDie) ++
  ZStream.fromZIO(Console.printLine("World!").orDie) ++
```

Nel caso di una funzione asincrona, sfruttando il metodo `ZStream.async` è possibile realizzare uno _stream_ che viene alimentato con i risultati di una specifica _callback_.
```scala
// Asynchronous Callback-based API
def registerCallback(
    name: String,
    onEvent: Int => Unit,
    onError: Throwable => Unit
): Unit = ???

// Lifting an Asynchronous API to ZStream
val stream = ZStream.async[Any, Throwable, Int] { cb =>
  registerCallback(
    "foo",
    event => cb(ZIO.succeed(Chunk(event))),
    error => cb(ZIO.fail(error).mapError(Some(_)))
  )
}
```

Inoltre il costruttore `repeat`, con le sue varianti, consente di costruire _stream_ sempre più complessi.
```scala
val repeatZero: ZStream[Any, Nothing, Int] = 
  ZStream.repeat(0)

val repeatZeroEverySecond: ZStream[Any, Nothing, Int] = 
  ZStream.repeatWithSchedule(0, Schedule.spaced(1.seconds))

val randomInts: ZStream[Any, Nothing, Int] =
  ZStream.repeatZIO(Random.nextInt)
```

Tramite il costruttore `unfold` è possibile descrivere un'operazione che, preso un valore iniziale, genera una struttura dati ricorsiva a partire dalla funzione passata come secondo parametro di input.
```scala
object ZStream {
  def unfold[S, A](s: S)(f: S => Option[(A, S)]): 
    ZStream[Any, Nothing, A] = ???
}
```
Il parametro `s` rappresenta uno stato iniziale, mentre `f` una funzione che verrà applicata a `s` per produrre i risultati in uscita. Questi possono essere due tipi:

- `None`: causerà la terminazione dello _stream_;
- `A`: il valore prodotto diventerà il nuovo stato `s`, valido per la computazione successiva.

```scala
def fromList[A](list: List[A]): ZStream[Any, Nothing, A] =
  ZStream.unfold(list) {
    case h :: t => Some((h, t))
    case Nil    => None
  }
```
All'operatore `unfold` si aggiunge la variante `unfoldZIO` che consente di valutare un _effect_ all'interno di `f`.

Infine `ZStream` permette di integrare del codice di libreria Java, nello specifico `java.nio` e `java.util.stream`. Di seguito vengono presentati alcuni esempi applicativi.
```scala
val stream: ZStream[Any, IOException, Byte] = 
  ZStream.fromInputStream(new FileInputStream("file.txt"))

val stream: ZStream[Any, IOException, Byte] =
  ZStream.fromResource("file.txt")

val stream: ZStream[Any, Throwable, Int] = 
  ZStream.fromJavaStream(java.util.stream.Stream.of(1, 2, 3))
```

## Esecuzione degli Stream

L'esecuzione di uno `ZStream` si struttura sempre in due passaggi:

1. lo _stream_ viene eseguito all'interno di un _effect_ di `ZIO`, al fine di ricavare una sua descrizione;
2. l'_effect_ ottenuto deve essere a sua volta eseguito tramite `unsafeRun`.

Anche in questo caso `ZStream` fornisce degli operatori che facilitano l'attività di consumo dei flussi. Tra questi si ricorda `foreach` che permette di applicare una certa funzione a ogni elemento dello _stream_.
```scala
ZStream.fromIterable(0 to 100).foreach(printLine(_))
```

Un altro operatore è il classico `fold` che riduce gli elementi dello _stream_ attraverso la funzione passata, e restituisce un _effect_ `ZIO` contenente il risultato.
```scala
val s1 = ZStream(1, 2, 3, 4, 5).runFold(0)(_ + _)
val s2 = ZStream.iterate(1)(_ + 1).runFoldWhile(0)(_ <= 5)(_ + _)
```

Infine a questi si aggiunge `ZSink`[^8], che può essere rappresentato come una funzione che riceve in input un numero variabile di elementi e che produce un solo valore in uscita. 
```scala
val sum: UIO[Int] = ZStream(1,2,3).run(ZSink.sum)
```

[^8]: Per approfondire: [https://zio.dev/zsink](https://zio.dev/reference/stream/zsink/)