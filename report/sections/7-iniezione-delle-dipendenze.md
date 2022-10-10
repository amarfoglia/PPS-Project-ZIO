# Dependency injection

Come già anticipato, `ZIO` può essere modellato concettualmente come:
```scala
type ZIO[-R, +E, +A] = ZEnvironment[R] => Either[Cause[E], A]
```
`R` può essere descritto come il _contesto_ (o insieme di servizi) necessario ad `R` stesso per eseguire. Ad esempio, si potrebbe realizzare un _workflow_ che si avvale di un contesto `Request`, al fine di accedere alle informazioni relative alla richiesta http corrente. Una sua possibile rappresentazione:
```scala
trait Request
lazy val workflow: ZIO[Request & Scope, Throwable, Unit] =
  ???
```
La firma di `workflow` esplicita chiaramente le dipendenze tramite `Request` e `Scope`. Inoltre la funzione potrà essere eseguita solo a seguito della risoluzione delle dipendenze.

Di seguito vengono elencate alcune proprietà relative all'_environment type_ :

- `R` impostato a `Any` rappresenta un _workflow_ che non richiede servizi ai fini dell'esecuzione;
- `Any` può essere visto come un insieme vuoto di servizi (elemento neutro);
- `&` è sia associativo che commutativo, quindi l'ordine con cui vengono passati i servizi non ha importanza.

`ZIO` permette di interagire con l'_environment_ tramite due funzioni fondamentali: `environment` e `service`. La prima introduce una dipendenza all'interno del _workflow_, con la quale è possible accedere all'insieme di servizi specificati nella firma. Mentre `service` permette l'accesso diretto a un singolo servizio.

```scala
object ZIO {
  def environment[R]: ZIO[ZEnvironment[R], Nothing,
    ZEnvironment[R]] = ???

  def service[R]: ZIO[ZEnvironment[R], Nothing, R] =
    ???
}
```
Indipendentemente dal metodo utilizzato, una volta recuperato un certo servizio dall'_environment_, questo può essere manipolato sfruttando gli operatori di `ZIO` come `map` e `flatMap`.

Inoltre, tramite `R` è possibile fare riferimento a un servizio non ancora disponibile all'interno dell'_effect_, così che la sua implementazione possa essere posticipata fino all'atto di esecuzione. Quest'ultima può essere fornita avvalendosi dell'operatore `provideEnvironment`, che concettualmente rimuove le dipendenze introdotte da `environment`.

Generalmente l'_environment_ viene utilizzato all'interno delle librerie `ZIO` per rappresentare una funzionalità valida localmente, che può cioè essere eliminata alla chiusura dell'_environment_. Un esempio concreto è chiaramente lo `Scope`; solitamente un'applicazione si compone di tanti `Scope` così da garantire il _principio di singola responsabilità_.

## The Onion architecture

Un altro caso d'uso dell'_environment_ può essere la definizione delle dipendenze di un certo _workflow_. Si prenda come esempio una semplice applicazione che interagisce (legge e scrive) con un _repository_ di GitHub; la logica sarebbe suddivisa in almeno tre "strati" (_layers_):

- ***business logic***: descrive il comportamento del programma indipendentemente dagli aspetti implementati dei livelli superiori;
- ***Github API logic***: descrive come la _business logic_ possa essere tradotta nel dominio dell'API di Github;
- ***http logic***: implementa la logica relativa alla creazione di una richiesta http e alla gestione dei risultati. Solitamente a questo livello si adottano librerie esterne come `zio-http`. 

Se non ci fosse una netta suddivisione tra i livelli sopra elencati, un eventuale modifica alla _business logic_ provocherebbe un effetto a cascata che renderebbe l'attività di _refactoring_ ardua e pericolosa (introduzione di _bug_). Inoltre, la mancata suddivisione delle responsabilità inficerebbe sia sullo sviluppo di test nel dettaglio, sia sulla produttività dei _team_ poiché impossibilitati a lavorare indipendentemente.

La soluzione è la _Onion architecture_, la cui idea di base è rappresentare ciascuno di questi "strati" come un servizio a se stante. Seguendo l'analogia del nome, la _business logic_ si trova al centro dell'architettura e viene progressivamente tradotta, dagli strati superiori, in qualcosa di più vicino al "mondo esterno".

Riprendendo l'esempio precedente, ogni _layer_ può essere implementato esclusivamente in termini del successivo (più esterno), come nel caso di `BusinessLogic` che necessita del servizio `Github`.
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
      _      <- ZIO.getOrFail(issues.headOption).flatMap { issue =>
                  github.postComment(issue, comment)
                }
  } yield ()
}
```
La soluzione fornita, oltre ad essere fortemente dichiarativa, permette di delegare il "come" di `postComment` al _layer_ successivo, in questo caso `Github`.

Riassumendo, la _onion architecture_ genera una serie di benefici:

- fornisce un modo naturale per scomporre i problemi in parti più piccole, ciascuna presa in carico da uno specifico servizio.
- consente a _team_ diversi di focalizzarsi su servizi differenti;
- rende il codice pienamente testabile poiché ogni servizio, se visto come un modulo a se stante, può essere facilmente sostituito fornendo una nuova implementazione.

A seguito dell'implementazione dei servizi, si passa alla fase di "montaggio", durante la quale le dipendenze vengono risolte.
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

La costruzione di ogni _layer_ può richiedere delle fasi di inizializzazione e di finalizzazione, quindi anche l'attività finale di "montaggio" può diventare complessa e difficile da comprendere. A tal proposito `ZIO` fornisce un tipo di dato chiamato `ZLayer`.
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

La funzione `fromFunction` nonostante sia coincisa, non fornisce un supporto per le fasi di inizializzazione e di finalizzazione, fondamentali nel caso del servizio `HttpLive`. Queste possono essere definite combinando il costruttore `ZLayer.scoped` con la _for comprehension_ e recuperando eventuali dipendenze tramite l'operatore `ZIO.service`.
```scala
object HttpLive {
  val layer: ZLayer[HttpConfig, Throwable, Http] =
    ZLayer.scoped {
      for {
        config <- ZIO.service[HttpConfig]
        http   <- ZIO.succeed(HttpLive(config))
        _      <- http.start
        _      <- ZIO.addFinalizer(http.shutdown)
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

Nel caso in cui, fosse possibile specificare solo parte delle dipendenze, `ZIO` mette a disposizione gli operatori `provideSome` e `makeSome`. Questi trovano spesso impiego nello sviluppo di test poiché permetto di fornire rapidamente configurazioni differenti del programma.

In conclusione, l'_environment type_ di `ZIO` offre gli strumenti necessari per risolvere l'intera gamma di problemi di iniezione delle dipendenze (_dependency injection_), promuovendo una corretta organizzazione del codice e favorendone la leggibilità. Inoltre ogni _layer_ permette di descrivere la logica di inizializzazione e finalizzazione associata al servizio, garantendo che questo venga istanziato una sola volta, indipendentemente da quante volte appare all'interno del grafo delle dipendenze. 