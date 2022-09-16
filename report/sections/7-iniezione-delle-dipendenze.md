# Dependency injection

Come già precedentemente anticipato, ZIO può essere modellato concettualmente come:
```scala
type ZIO[-R, +E, +A] = ZEnvironment[R] => Either[Cause[E], A]
```
Si può anche assumere `R` come il _contesto_ (o insieme di servizi) necessario ad `R` stesso per eseguire. Ad esempio, si potrebbe realizzare un _workflow_ che si avvale di un contesto `Request`, per accedere ad informazioni relative alla richiesta HTTP corrente. Una sua possibile rappresentazione:
```scala
trait Request
lazy val workflow: ZIO[Request & Scope, Throwable, Unit] =
  ???
```
La firma di `workflow` esplicita chiaramente le dipendenze con `Request` e `Scope`, che solo una volta risolte permetteranno l'esecuzione della funzione. 

Il generico `E` (_environment type_) porta con se alcune proprietà:

- `E` impostato a `Any` rappresenta un _workflow_ che non richiede servizi ai fini dell'esecuzione;
- `Any` rappresenta quindi un insieme vuoto di servizi (elemento neutro);
- `&` è sia associativo che commutativo, quindi l'ordine con cui vengono passati i servizi non ha importanza.

ZIO fornisce due operazioni fondamentali per lavorare con l'_ambiente_, il primo è `environment` e permette di accedere ai servizi (di un dato _ambiente_) introducendo delle dipendenze con questi. 
```scala
object ZIO {
  def environment[R]: ZIO[ZEnvironment[R], Nothing,
    ZEnvironment[R]] = ???
}
```
Il secondo operatore è `service`, che a differenza del primo permette di accedere ad un singolo servizio:
```scala
object ZIO {
  def service[R]: ZIO[ZEnvironment[R], Nothing, R] =
    ???
}
```
Indipendentemente dal metodo utilizza, una volta acquisito un certo servizio dall'_environment_, questo può essere manipolato sfruttando gli operatori di ZIO come `map` e `flatMap`.

Inoltre `R` permette di sfruttare all'interno di un _effect_ un servizio non ancora disponibile, la cui implementazione può essere posticipata fino all'atto di esecuzione. Proprio per questo motivo, al fine di eliminare le dipendenze create da `environment`, è necessario utilizzare l'operatore `provideEnvironment` così da specificare l'implementazione dei servizi richiesti. 

La situazione più comune in cui l'_environment_ viene utilizzato nelle librerie ZIO è per rappresentare un contesto o una funzionalità che può essere eliminata localmente. Ad esempio, in genere non si dispone di uno `Scope` per l'intera applicazione, ma si hanno vari `Scope` che rappresentano la validità di diverse risorse.

## The Onion architecture

Un altro caso d'uso dell'_environment_ può essere la definizione delle dipendenze di un certo _workflow_. Si prenda come esempio una semplice applicazione che interagisce (legge e scrive) con un _repository_ di GitHub; la logica sarebbe suddivisa in almeno tre "strati" (_layers_):

- ***business logic***: descrive l'intenzione del programma indipendentemente dal come verrà fatto;
- ***Github API logic***: descrive come la _business logic_ possa essere tradotta nel dominio dell'API di Github;
- ***http logic***: tramite una libreria (ad esempio `zio-http`) la logica necessaria per creare una richiesta e per catturare la risposta.

Se si mescolassero i livelli appena elencati, le modifiche alla _business logic_ provocherebbero un effetto a cascata che renderebbe l'attività di _refactoring_ ardua e prona all'aggiunta di _bug_. Inoltre il codice sarebbe difficilmente testabile nel dettaglio e la mancata suddivisione delle responsabilità non permettere a _team_ diversi di lavorare indipendentemente per area di competenza.

La soluzione è la _Onion architecture_, la cui idea di base è rappresentare ciascuno di questi "strati" come un servizio a se stante. Seguendo l'analogia del nome, la _business logic_ si trova al centro della cipolla e ogni altro strato traduce questa logica in qualcosa di più vicino al "mondo esterno". 

Riprendendo l'esempio precedente, ogni _layer_ può essere implementato esclusivamente in termini del successivo strato esterno, come nel caso di `BusinessLogic` che necessita del servizio `Github`.
```scala
trait Issue
final case class Comment(text: String) extends Issue

trait BusinessLogic {
  def run: ZIO[Any, Throwable, Unit]
}

trait Github {
  def getIssues(
    organization: String
  ): ZIO[Any, Throwable, Chunk[Issue]]
  def postComment(
    issue: Issue,
    comment: Comment
  ): ZIO[Any, Throwable, Unit]
}

trait Http {
  def get(
    url: String
  ): ZIO[Any, Throwable, Chunk[Byte]]
  def post(
    url: String,
    body: Chunk[Byte]
  ): ZIO[Any, Throwable, Chunk[Byte]]
}
```

Il servizio `BusinessLogic` può essere implementato semplicemente nel seguente modo:
```scala
final case class BusinessLogicLive(github: Github)
  extends BusinessLogic {
    val run: ZIO[Any, Throwable, Unit] =
    for {
      issues <- github.getIssues("zio")
      comment = Comment("I am working on this!")
      _ <- ZIO.getOrFail(issues.headOption).flatMap { issue =>
        github.postComment(issue, comment)
      }
  } yield ()
}
```
La soluzione fornita oltre ad essere fortemente dichiarativa permette di delegare il "come" al _layer_ successivo, in questo caso `Github`.

Riassumendo, la _onion architecture_ genera una serie di benefici:

- fornisce un modo naturale per scomporre i problemi in parti più piccole. Ogni servizio deve risolvere un problema specifico;
- consente a _team_ diversi di focalizzarsi su servizi differenti;
- rende il codice pienamente testabile poiché ogni servizio, se visto come un modulo a se stante, può essere facilmente sostituito sulla base dell'implementazione.

A seguito dell'implementazione dei servizi, si passa alla fase di "montaggio", in cui le dipendenze vengono risolte.
```scala
object Main extends ZIOAppDefault {
  val http: Http = HttpLive()
  val github: Github = GithubLive(http)
  val businessLogic: BusinessLogic = BusinessLogicLive(github)

  val run =
    businessLogic.run
}
```

## ZLayer

La costruzione di ogni _layer_ può richiedere delle fasi di inizializzazione e di finalizzazione, quindi anche l'attività finale di montaggio può diventare complessa e difficile da comprendere. A tal proposito ZIO fornisce un tipo di dato chiamato `ZLayer`.
```scala
trait ZLayer[RIn, E, ROut]
```
`ZLayer` può essere rappresentato come una "ricetta" che, presi un insieme di servizi (`RIn`), ne restituisce di nuovi (`ROut`) oppure fallisce con un certo errore `E`. Il modo più semplice per costruire uno `ZLayer` è sfruttare la funzione `ZLayer.fromFunction`.
```scala
object BusinessLogicLive {
  val layer: ZLayer[Github, Nothing, BusinessLogic] =
    ZLayer.fromFunction(BusinessLogicLive(_))
}
```
Im questa soluzione, il costruttore `BusinessLogic.apply` può essere espresso come una funzione `Github => BusinessLogic`. 

La funzione `fromFunction` nonostante sia coincisa, non fornisce un supporto per le fasi di inizializzazione e di finalizzazione. Queste possono essere implementate combinando il costruttore `ZLayer.scoped` con la _for comprehension_. Nel caso del servizio `HttpLive`, la finalizzazione consiste nell'invocazione del metodo `shutdown`.  Infine è possibile recuperare una dipendenza tra servizi sfruttando l'operatore `ZIO.service`, introdotto precedentemente.
```scala
object HttpLive {
  val layer: ZLayer[HttpConfig, Throwable, Http] =
    ZLayer.scoped {
      for {
        config <- ZIO.service[HttpConfig]
        http <- ZIO.succeed(HttpLive(config))
        _ <- http.start
        _ <- ZIO.addFinalizer(http.shutdown)
      } yield http
    }
}
```
<!-- 
La funzione `fromFunction` nonostante sia coincisa, non fornisce un supporto per le fasi di inizializzazione e di finalizzazione. La prima può essere implementata tramite il metodo `apply` di `ZLayer` combinato con la _for comprehension_. Nel caso del servizio `Http`:
```scala
object HttpLive {
  val layer: ZLayer[Any, Throwable, Http] =
    ZLayer {
      for {
        http <- ZIO.succeed(HttpLive())
        _ <- http.start
      } yield http
    }
}
```

```scala
object HttpLive {
  val layer: ZLayer[Any, Throwable, Http] =
  ZLayer.scoped {
    for {
      http <- ZIO.succeed(HttpLive())
      _ <- http.start
      _ <- ZIO.addFinalizer(http.shutdown)
    } yield http
  }
}
``` -->
Una volta realizzati i diversi _layer_, si può procedere con il loro assemblaggio; il compilatore supporterà questa attività evidenziando le dipendenze mancanti. L'implementazione del servizio `BusinessLogic` potrà essere fornita nel seguente modo:
```scala
object Main extends ZIOAppDefault {
  val run =
    ZIO.serviceWithZIO[BusinessLogic](_.run)
      .provide(
        BusinessLogicLive.layer,
        GithubLive.layer,
        HttpLive.layer
      )
}
```

Nel caso in cui, fosse possibile specificare solo parte delle dipendenze, ZIO mette a disposizione gli operatori `provideSome` e `makeSome`. Queste funzionalità trovano spesso impiego durante l'attività di testing, poiché permettono di fornire facilmente diverse implementazioni.

In conclusione, l'_environment type_ di ZIO offre gli strumenti necessari per risolvere l'intera gamma di problemi di iniezione delle dipendenze (_dependency injection_), promuovendo una corretta organizzazione del codice e favorendone la leggibilità. Inoltre ogni _layer_ permette di descrivere la logica di inizializzazione e finalizzazione associata al servizio, garantendo che questo venga istanziato una sola volta, indipendentemente da quante volte appare all'interno del grafo delle dipendenze. 