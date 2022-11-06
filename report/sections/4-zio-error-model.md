# ZIO Error Model

Lo sviluppo di applicazioni complesse comporta una gestione degli errori altrettanto complicata, poiché queste possono fallire in svariati modi. Solamente un forte ed adeguato utilizzo del _type system_ di Scala permette di raggiungere elevati canoni di robustezza e resilienza delle applicazioni.

`ZIO` definisce tre possibili tipologie di fallimento:

- **_Failures_**: modellano scenari di fallimento prevedibili e potenzialmente recuperabili, infatti vengono catturati dall'_error type_ dell'_effect_;
- **_Defects_**: sono fallimenti non rappresentanti dall'_error type_ dell'_effect_, modellano errori non prevedibili oppure non recuperabili. L'unico modo per gestirli nei livelli superiori è propagarli lungo lo _stack_ dell'applicazione, eventualmente convertendoli in _failure_;
- **_Fatals_**: sono errori catastrofici che determinano la terminazione immediata del programma.

## Gestione imperativa vs dichiarativa

Seguendo un'approccio imperativo, quando si presenta uno stato non valido, viene lanciata un'eccezione che deve essere gestita tramite `try`/`catch`. Nel caso dichiarativo gli errori diventano dei valori, quindi non è necessario avvalersi delle eccezioni interrompendo il flusso del programma. Questo secondo approccio genera diversi benefici:

- _referential transparency_: quando viene lanciata un'eccezione, la trasparenza referenziale viene meno. Al contrario, un approccio di gestione degli errori dichiarativo rende indipendente il comportamento del programma dalla posizione in cui vengono valutate le diverse espressioni;
- _type safety_: il tracciamento del tipo dell'errore lungo gli _effect_, abilita il compilatore a prevenire la scrittura di codice _unsafe_ e permette di disinteressarsi degli aspetti implementativi;
- _exhaustive checking_: nel caso di `try` e `catch`, il compilatore non ha un ruolo attivo nella gestione degli errori, quindi non è possibile sviluppare _total function_[^7];
- _error model_: il modello di errore basato sulle istruzioni `try`/`catch`/`finally` non consente di catturare multiple eccezioni, andando così a perdere del contenuto informativo.

[^7]: funzione definita per tutti i valori del dominio.

## Cause

`ZIO` formalizza la distinzione tra _failures_ e _defects_ tramite un tipo di dato chiamato `Cause`. 
```scala
sealed trait Cause[+E]

object Cause {
  final case class Die(t: Throwable) extends Cause[Nothing]
  final case class Fail[+E](e: E) extends Cause[E]
}
```
`Cause[E]` è un _sealed trait_ con diversi sottotipi, i più importanti sono `Die` e `Fail`, che permettono di catturare tutti i possibili scenari di fallimento di un _effect_. `Fail` descrive errori della categoria _failures_ mentre `Die` i _defects_. 

Tramite l'operatore `catchAllCause` è possibile effettuare _pattern matching_ sui sottotipi di `Cause` catturando gli eventuali errori, tra cui l'interruzione delle _fiber_:
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

## Gestione delle Failure

In `ZIO` è possibile modellare una _failure_ tramite il costruttore `fail`.
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
L'esecuzione di tali _effect_ produrrà in output il seguente _stack trace_:
```bash
timestamp=2022-03-08T17:55:50.002161369Z level=ERROR 
  thread=#zio-fiber-0 message="Exception in thread 'zio-fiber-2'
  java.lang.String: Oh uh! at <empty>.MainApp.run(MainApp.scala:4)"
```

Le _failure_ possono essere catturate e recuperate efficacemente tramite l'operatore `catchAll`:
```scala
trait ZIO[-R, +E, +A] {
  def catchAll[R1 <: R, E2, A1 >: A](h: E => ZIO[R1, E2, A1]):
    ZIO[R1, E2, A1]
}
```
Un esempio di utilizzo che include un _match cases_ esaustivo:
```scala
sealed trait AgeValidationException extends Exception
case class NegativeAgeException(age: Int)
  extends AgeValidationException
case class IllegalAgeException(age: Int)
  extends AgeValidationException

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
La mancata cattura di un errore comporterebbe la sostituzione della _failure_ originale con un `MatchError` _defect_. Un'alternativa meno generale a `catchAll` è `catchSome` che permette la cattura e il recupero parziale, cioè solo degli errori specificati.

## Gestione dei Defects

La maggior parte degli operatori per la gestione degli errori non contemplano i _defect_. La gestione di fallimenti sconosciuti e imprevedibili andrebbe ad appesantire inutilmente le firme dei metodi.

Se si volesse implementare una funzione `divide` che rappresenta la divisione tra due numeri, tramite il costruttore `ZIO#die`, e un apposito `Throwable`, è possibile descrivere il fallimento (_defect_) della funzione nel caso di denominatore uguale a zero.
```scala
import zio._

def divide(a: Int, b: Int): ZIO[Any, Nothing, Int] =
  if (b == 0)
    ZIO.die(new ArithmeticException("divide by zero"))
  else
    ZIO.succeed(a / b)
```
Siccome si sta modellando un _defect_, l'`ArithmeticException` non viene catturata dalla firma dell'_effect_ (tipizzata a `Nothing`). Nel caso specifico, la gestione della divisione per zero può essere delegata direttamente alla `JVM`.
Questo permette di definire un programma equivalente evitando il controllo del denominatore.
```scala
def divide(a: Int, b: Int): ZIO[Any, Nothing, Int] =
  ZIO.succeed(a / b)
```
Questo esempio è a sostegno della filosofia _let it crash_; poiché ogni eccezione in un `ZIO` _effect_ viene tradotta automaticamente in un `ZIO` _defect_, può essere sensato non gestire l'errore favorendo la semplicità del codice. Nonostante questo sia l'approccio di default, esistono situazioni in cui è opportuno gestire i _defect_, per esempio in un sistema di _logging_. `ZIO` fornisce una famiglia di operatori in grado di gestire i _defect_ in aggiunta alle _failure_. Tra questi si ricorda `sandbox`: espone l'intera causa di fallimento all'interno del canale di errore dell'_effect_.
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

Anche per i _defect_ `ZIO` offre delle operazioni di cattura.
```scala
trait ZIO[-R, +E, +A] {
  def catchAllDefect[R1 <: R, E1 >: E, A1 >: A](
    h: Throwable => ZIO[R1, E1, A1]
  ): ZIO[R1, E1, A1]

  def catchSomeDefect[R1 <: R, E1 >: E, A1 >: A](
    pf: PartialFunction[Throwable, ZIO[R1, E1, A1]]
  ): ZIO[R1, E1, A1]
}
```
Siccome i _defect_ sono errori non previsti, la regola base è quella di catturarli allo scopo di tracciarne il contenuto e non di effettuare operazioni di recupero.
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

### Conversione degli errori in Defects

Proseguendo lungo i livelli dell'applicazione, si passa da aspetti di basso livello a quelli legati alla _business logic_, quindi diventerà sempre più chiaro quali tipi di errori saranno recuperabili e quali no. Per esempio una funzione di supporto per la lettura di file potrebbe ritornare uno `ZIO[Any, IOException, String]`, ma l'errore `IOException` nei livelli superiori può assumere il significato di _defect_. Per esempio, l'assenza di un file di configurazione potrebbe comportare la chiusura irrecuperabile dell'applicazione. 

Per convertire gli errori in _defect_ si utilizza il metodo `orDie` che, preso un _effect_, può fallire con qualsiasi sottotipo di `Throwable`. 
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

Nel caso di codice procedurale, in cui si valuta un'espressione alla volta, è ragionevole assumere che una computazione possa fallire con un'unica eccezione. In alcuni casi questa assunzione può non essere valida; un programma concorrente potrebbe essere composto da più processi in grado di fallire. Considerando un database distribuito, al fine di ridurre la latenza, la richiesta di una risorsa potrebbe essere suddivisa in due richieste distinte, eseguite parallelamente. Dato che entrambe potrebbero fallire, tramite `ZIO` è possibile catturate i due errori prodotti sfruttando i sottotipi di `Cause`.
```scala
object Cause {
  final case class Both[+E](left: Cause[E], right: Cause[E])
    extends Cause[E]
  final case class Then[+E](left: Cause[E], right: Cause[E])  
    extends Cause[E]
}
```
Il tipo di dato `Both` rappresenta due cause di fallimento che si verificano in maniera concorrente, mentre `Then` descrive due errori sequenziali. Ovviamente ogni `Cause` può contenere internamente altre cause.

A livello di API, nel caso di computazioni concorrenti, `ZIO` offre il metodo `parallelErrors` che consente di esplicitare tutti i fallimenti all'interno dell'_error channel_. 
```scala
val result: ZIO[Any, ::[String], Nothing] =
  (ZIO.fail("Oh uh!") <&> ZIO.fail("Oh Error!")).parallelErrors
```

## Combinare Effects

Quando si tenta di combinare due _effect_ tramite operatori come `zip`, il tipo di errore risultate sarà il supertipo più specifico  di entrambi. Per esempio, considerando il codice sottostante, la combinazione di `callApi` e `queryDb` produrrà un _effect_ cha ha come _success type_ la coppia dei valori dei primi due, e come errore la superclasse `Exception`.
```scala
final case class ApiError(message: String)
  extends Exception(message)
final case class DbError(message: String)
  extends Exception(message)

trait Result

lazy val callApi: ZIO[Any, ApiError, String] = ???
lazy val queryDb: ZIO[Any, DbError, Int] = ???

lazy val combine: ZIO[Any, Exception, (String, Int)] =
  callApi.zip(queryDb)
```

Se gli errori non condividono una struttura comune, l'operatore di combinazione non potrà fare altro che restituire `Any`, perdendo la causa di fallimento dell'_effect_. In questo caso l'unica soluzione per evitare la perdita di informazioni è cambiare esplicitamente il tipo di errore tramite il metodo `ZIO.mapError`: converte una _failure_ in un _effect_ che fallisce con l'errore scelto.
```scala
type DocumentError = Either[InsufficientPermission, FileIsLocked]

lazy val result: ZIO[Any, DocumentError, Unit] =
  shareDocument("347823").mapError(Left(_))
    .zip(moveDocument("347823", "/temp/").mapError(Right(_)))
    .unit
```
Nell'esempio, le classi di errore disgiunte `InsufficientPermission` e `FileIsLocked` vengono racchiuse all'interno di `Either`, il quale funge da sovratipo.

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