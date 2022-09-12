# ZIO Error Model

Lo sviluppo di applicazioni complesse comporta una gestione degli errori altrettanto complicata, poiché queste possono fallire in svariati modi. Solamente un forte ed adeguato utilizzo del _type system_ di Scala permette di raggiungere elevati cannoni di robustezza e resilienza delle applicazioni. Tra i principali benefici:

ZIO definisce tre possibili tipologie di fallimento:

- **_Failures_**: modellano scenari di fallimento prevedibili e potenzialmente recuperabili, infatti vengono catturati dall'_error type_ dell'_effect_;
- **_Defects_**: sono fallimenti non rappresentanti dall'_error type_ del _effect_, poiché modellano errori non prevedibili oppure non recuperabili. Devono essere propagati lungo lo _stack_ dell'applicazione al fine di gestirli, ad esempio convertendoli in _failure_, nei livelli superiori;
- **_Fatals_**: sono errori catastrofici che determinano la terminazione immediata del programma.

## Gestione imperativa vs dichiarativa
Seguendo un'approccio imperativo, quando si presenta uno stato non valido, viene lanciata un'eccezione che deve essere gestita tramite `try`/`catch`. Nel caso dichiarativo gli errori diventano dei valori, quindi non è necessario avvalersi delle eccezioni interrompendo il flusso del programma. Questo secondo approccio genera diversi benefici:

- _referential transparency_: quando viene lanciata un'eccezione, la trasparenza referenziale viene meno. Mentre un approccio di gestione degli errori dichiarativo rende indipendente il comportamento del programma dalla posizione in cui vengono valutate le diverse espressioni;
- _type safety_: tracciare il tipo dell'errore lungo gli _effect_, abilita il compilatore a prevenire la scrittura di codice _unsafe_ ed evita di dover conoscere gli implementativi;
- _exhaustive checking_: nel caso di `try` e `catch`, il compilatore ignora il tipo dei possibili errori generati, quindi non permette di implementare _total function_ per la loro gestione;
- _error model_: il modello di errore basato sulle istruzioni `try`/`catch`/`finally` consente di catturare una sola eccezione, quindi nel caso di eccezioni multiple si perderebbe del contenuto informativo.


## Cause
ZIO formalizza la distinzione tra _failures_ e _defects_ tramite un tipo di dato chiamato `Cause`. 
```scala
sealed trait Cause[+E]

object Cause {
  final case class Die(t: Throwable) extends Cause[Nothing]
  final case class Fail[+E](e: E) extends Cause[E]
}
```
`Cause[E]` è un _sealed trait_ con diversi sottotipi (i più importanti sono `Die` e `Fail`) che permettono di catturare tutti i possibili scenari di fallimento di un _effect_. `Fail` descrive errori della categoria _failures_ mentre `Die` i _defects_. 

Tramite l'operatore `catchAllCause` è possibile effettuare _pattern matching_, sui sottotipi di `Cause`, e catturare i diversi errori generati, tra cui l'interruzione delle _fiber_:
```scala
val exceptionalEffect = ZIO.attempt(???)

exceptionalEffect.catchAllCause {
  case Cause.Empty =>
    ZIO.debug("no error caught")
  case Cause.Fail(value, _) =>
    ZIO.debug(s"a failure caught: $value")
  case Cause.Die(value, _) =>
    ZIO.debug(s"a defect caught: $value")
  case Cause.Interrupt(fiberId, _) =>
    ZIO.debug(s"a fiber interruption caught with the fiber id: $fiberId")
  // ...
}
```

## Exit
Un tipo di dato strettamente correlato a `Cause` è `Exit`, il quale rappresenta tutte le possibili modalità di terminazione di un _effect_ in esecuzione. 
```scala
sealed trait Exit[+E, +A]

object Exit {
  final case class Success[+A](value: A) extends Exit[Nothing, A]
  final case class Failure[+E](cause: Cause[E])
    extends Exit[E, Nothing]
}
```
Un _effect_ di tipo `ZIO[R, E, A]` può terminare correttamente con un valore di tipo `A`, oppure fallire con un valore `Cause[E]`.

## Gestione delle _Failures_
Tramite ZIO è possibile modellare una _failure_ tramite il costruttore `fail`.
```scala
trait ZIO {
  def fail[E](error: => E): ZIO[Any, E, Nothing]
}
```
Un suo semplice esempio applicativo può essere il seguente:
```scala
import zio._

object MainApp extends ZIOAppDefault {
  def run = ZIO.succeed(5) *> ZIO.fail("Oh uh!")
}
```
L'esecuzione di tali _effects_ produrrà in output il seguente _stack trace_:
```scala
timestamp=2022-03-08T17:55:50.002161369Z level=ERROR 
  thread=#zio-fiber-0 message="Exception in thread 'zio-fiber-2'
  java.lang.String: Oh uh! at <empty>.MainApp.run(MainApp.scala:4)"
```

### Cattura delle _Failures_

Le _failures_ possono essere catturate e recuperate efficacemente tramite l'operatore `catchAll`:
```scala
trait ZIO[-R, +E, +A] {
  def catchAll[R1 <: R, E2, A1 >: A](h: E => ZIO[R1, E2, A1]):
    ZIO[R1, E2, A1]
}
```
Un esempio di utilizzo che include un _match cases_ esaustivo:
```scala
sealed trait AgeValidationException extends Exception
case class NegativeAgeException(age: Int) extends AgeValidationException
case class IllegalAgeException(age: Int) extends AgeValidationException

def validate(age: Int): ZIO[Any, AgeValidationException, Int] =
  if (age < 0)
    ZIO.fail(NegativeAgeException(age))
  else if (age < 18)
    ZIO.fail(IllegalAgeException(age))
  else ZIO.succeed(age)

val result: ZIO[Any, Nothing, Int] =
  validate(20)
    .catchAll {
      case NegativeAgeException(age) =>
        ZIO.debug(s"negative age: $age").as(-1)
      case IllegalAgeException(age) =>
        ZIO.debug(s"illegal age: $age").as(-1)
    }
```
La mancata cattura di un errore comporterebbe la sostituzione della _failure_ originale con un `MatchError` _defect_. Un'alternativa meno generale a `catchAll` è `catchSome`: permette la cattura e il recupero solo di alcuni sottotipi di eccezioni.

## Gestione dei _Defects_

La maggior parte degli operatori per la gestione degli errori non contempla i _defects_, poiché non si hanno vantaggi nel rendere più complessa la firma degli operatori con l'intento di gestire fallimenti sconosciuti ed imprevedibili. 

Nel caso si volesse implementare una funzione `divide`, che rappresenta la divisione tra due numeri, un denominatore valorizzato a zero causerebbe il fallimento dell'_effect_. Questo può essere descritto attraverso il costruttore `ZIO#die` e un `Throwable` passatogli in input:
```scala
import zio._

def divide(a: Int, b: Int): ZIO[Any, Nothing, Int] =
  if (b == 0)
    ZIO.die(new ArithmeticException("divide by zero")) // Unexpected error
  else
    ZIO.succeed(a / b)
```
Il tipo dell'errore di ritorno della funzione `divide` è tipizzato a `Nothing`, ciò significa che non è previsto un fallimento (_failure_). Questo però non esclude l'assenza di _defects_, infatti nel caso di divisione per zero è la `JVM` stessa a lanciare un'`ArithmeticException`, quindi lo stesso programma può essere riscritto evitando il controllo del valore al denominatore:
```scala
def divide(a: Int, b: Int): ZIO[Any, Nothing, Int] =
  ZIO.succeed(a / b)
```
Questo esempio rafforza la filosofia _let it crash_, poiché ogni eccezione in un ZIO _effect_ viene tradotta automaticamente in un ZIO _defects_, può essere sensato non gestire l'errore favorendo la semplicità di codice. Nonostante questo sia l'approccio di default, esistono situazioni in cui è opportuno gestire i _defects_, per esempio un sistema di _logging_. ZIO fornisce una famiglia di operatori in grado di gestire i _defects_ in aggiunta alle _failures_. Tra questi si ricorda `sandbox`: espone l'intera causa del fallimento all'interno del canale di errore dell'_effect_.
```scala
trait ZIO[-R, +E, +A] {
  def sandbox: ZIO[R, Cause[E], A]
}
```
Come si evince dalla firma, tutti gli errori vengono rappresentati sotto forma di `Cause[E]` manipolabili tramite operatori come `ZIO#catchAll`. Una volta eseguite le operazioni di recupero necessarie, è possibile nascondere la causa di fallimento, ritornando alla sola gestione delle _failure_ (errori tipizzati), attraverso `unsandbox`.
```scala
val effect: ZIO[Any, String, String] =
  ZIO.succeed("primary result") *> ZIO.fail("Oh uh!")

effect            // ZIO[Any, String, String]
  .sandbox        // ZIO[Any, Cause[String], String]
  .catchSome(???) // ZIO[Any, Cause[String], String]
  .unsandbox      // ZIO[Any, String, String]
```

### Cattura dei _Defects_

Anche per i _defects_ ZIO mette a disposizione due operazioni per la loro cattura.
```scala
trait ZIO[-R, +E, +A] {
  def catchAllDefect[R1 <: R, E1 >: E, A1 >: A](h: Throwable => ZIO[R1, E1, A1]): ZIO[R1, E1, A1]

  def catchSomeDefect[R1 <: R, E1 >: E, A1 >: A](pf: PartialFunction[Throwable, ZIO[R1, E1, A1]]): ZIO[R1, E1, A1]
}
```
Siccome i _defects_ sono errori non previsti, la regola base è quella di catturarli al fine di tracciarne il contenuto, ad esempio tramite un sistema di logging, e non di effettuare operazioni di recupero.
```scala
ZIO.dieMessage("Boom!")
  .catchAllDefect {
    case e: RuntimeException if e.getMessage == "Boom!" =>
      ZIO.debug("Boom! defect caught.")
    case _: NumberFormatException =>
      ZIO.debug("NumberFormatException defect caught.")
    case _ =>
      ZIO.debug("Unknown defect caught.")
  }
```

### Convertire errori in _Defects_

Man mano che si avanza lungo i livelli dell'applicazione, si passa da aspetti di basso livello a quelli legati alla _business logic_ quindi diventerà sempre più chiaro quali tipi di errori sono recuperabili e quali no. Per esempio una funzione di supporto per la lettura di file potrebbe ritornare uno `ZIO[Any, IOException, String]`, ma l'errore `IOException` nei livelli superiori può assumere il significato di _defects_ poiché, per esempio, l'assenza di un file di configurazione potrebbe comportare la chiusura irrecuperabile dell'applicazione. 

Per convertire gli errori in _defects_ si utilizza il metodo `orDie` che preso un _effect_ può fallire con qualsiasi sottotipo di `Throwable`. 
```scala
def readFile(file: String): ZIO[Any, IOException, String] =
  ???
lazy val result: ZIO[Any, Nothing, String] =
  readFile("data.txt").orDie
```
<!-- Nel caso si voglia discriminare la tipologia di errori da trattare come _defects_, è possibile utilizzare la funzione `refineWith`.
```scala
def readFile(file: String): ZIO[Any, Throwable, String] =
  ???

def readFile2(file: String): ZIO[Any, IOException, String] =
  readFile(file).refineWith {
    case e : IOException => e
  }
```
Ogni tipo di errore escluso dal _match case_ sarà convertito in un _defects_. -->

## Fallimenti multipli

Nel caso di codice procedurale, in cui si valuta una espressione alla volta, è ragionevole assumere che una computazione possa fallire con un unica eccezione. In alcuni casi questa assunzione può non essere valida; in un programma concorrente potrebbero esserci più parti a fallire. Considerando un database distribuito, al fine di ridurre la latenza, la richiesta di una risorsa potrebbe essere suddivisa in due richieste distinte eseguite parallelamente. Dato che entrambe potrebbero fallire, tramite ZIO è possibile catturate i due errori prodotti sfruttando i sottotipi di `Cause`.
```scala
object Cause {
  final case class Both[+E](left: Cause[E], right: Cause[E])
    extends Cause[E]
  final case class Then[+E](left: Cause[E], right: Cause[E])  
    extends Cause[E]
}
```
Il tipo di dato `Both` rappresenta due cause di fallimento che si verificano in maniera concorrente, mentre `Then` descrive due errori sequenziali. Ovviamente ogni `Cause` all'interno dei due sottotipi può contenerne altre.

A livello di API nel caso di computazioni concorrenti, ZIO offre il metodo `parallelErrors` al fine di esporre tutti i fallimento all'interno dell'_error channel_. 
```scala
val result: ZIO[Any, ::[String], Nothing] =
  (ZIO.fail("Oh uh!") <&> ZIO.fail("Oh Error!")).parallelErrors
```


## Combinare _effects_ con diversi errori

Quando si cerca di combinare due _effects_ tramite operatori come `zip`, ZIO sceglie come tipo quello che più specifico come supertipo di entrambi. Per esempio, considerando due tipi di errore, `ApiError` e `DbError`, e due specifici _effects_:
```scala
final case class ApiError(message: String)
  extends Exception(message)
final case class DbError(message: String)
  extends Exception(message)

trait Result

lazy val callApi: ZIO[Any, ApiError, String] = ???
lazy val queryDb: ZIO[Any, DbError, Int] = ???
```
La combinazione dei due _effects_ ne produrrà un altro che ha come valore la coppia dei due, e come tipo di errore la superclasse, cioè `Exception`.
```scala
lazy val combine: ZIO[Any, Exception, (String, Int)] =
  callApi.zip(queryDb)
```

In alcuni casi gli errori potrebbero non condividere una struttura comune, quindi l'operatore tornerà `Any`, ma questo non permette di descrivere la causa di fallimento dell'_effect_. La soluzione è cambiare esplicitamente il tipo di errore in un altro tramite il metodo `ZIO.mapError`: converte un _effect_ che fallisce con un certo errore, in uno che fallisce con un altra causa.

```scala
type DocumentError = Either[InsufficientPermission, FileIsLocked]

lazy val result: ZIO[Any, DocumentError, Unit] =
  shareDocument("347823").mapError(Left(_))
    .zip(moveDocument("347823", "/temp/").mapError(Right(_)))
    .unit
```
In questo caso viene utilizzato il tipo di dato `Either` per racchiudere entrambe le possibilità di errore.

<!-- Nell'esempio presentato 
```scala
sealed trait ZIO[-R, +E, +A] {
  def orElse[R1 <: R, E2, A1 >: A](
    that: ZIO[R1, E2, A1]
  ): ZIO[R1, E2, A1] =
    ???
}
```

l'operatore `orElse` realizza un'operazione di _fallback_ che ritorna un _effect_ che descrive il tentativo di eseguire l'_effect_ nella parte sinistra, mentre restituisce l'effect di _fallback_ nel caso di fallimento. -->