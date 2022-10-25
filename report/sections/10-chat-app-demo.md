# Demo: chat app

Allo scopo di mettere in pratica i concetti approfonditi nelle sezioni precedenti, è stata sviluppata un'applicazione di messaggistica basata su _stream_. L'idea è quella di un servizio che permette agli utenti sia di accedere a delle stanze che scambiare messaggi. Le diverse entità interagiscono tramite flussi di eventi regolamentati da un protocollo di comunicazione che consente di filtrare gli eventi in entrata (`ClientCommand`) da quelli di uscita (`ServerCommand`).

L'API del servizio si compone principalmente di tre sorgenti dati: la lista delle stanze, il numero degli utenti connessi ad una specifica stanza ed i messaggi scambiati. Le ultime due vengono generate solo a seguito della selezione di una stanza. 

![Rappresentazione grafica degli _stream_ dell'API.\label{stream-pattern}](https://raw.githubusercontent.com/amarfoglia/PPS-Project-ZIO/main/report/sections/img/main_streams.png "Rappresentazione grafica degli _stream_ dell'API.")

Come mostrato nell'immagine \ref{stream-pattern}, per ogni _client_ connesso si andranno a creare diversi canali, ognuno adibito allo scambio di determinati messaggi. 

L'applicativo è una _build_ multi progetto che si struttura in tre moduli: `backend`, `frontend` e `common`. Al fine di realizzare un progetto _full-stack_ in Scala, il secondo modulo è stato sviluppato avvalendosi di [`Scala.js`](https://www.scala-js.org/). Questo ha permesso di definire un dominio (`common`) condiviso tra le parti di `backend` e `frontend`.  

## Backend

L'interfaccia principale del modulo di `backend` è `Controller`, questa espone un'API basata su `ZStream` i cui elementi, come visibile nell'immagine \ref{messages}, sono specifici eventi di dominio. 
```scala
trait Controller:
  def subscribe: UStream[ServerCommand]
  def subscribeRoom(roomId: RoomId):
    ZStream[Scope, NoSuchElementException, ServerRoomCommand]
  def handleClientCommand(command: ClientActionCommand): 
    Stream[NoSuchElementException | IOException, Unit]
```
La funzione `subscribe` descrive la sottoscrizione di un nuovo utente al servizio e implementa un canale addetto all'invio dell'elenco delle stanze disponibili. Allo stesso modo `subscribeRoom` permette di definire un nuovo flusso di dati contenente le informazioni relative a una determinata stanza: messaggi e numero di utenti connessi. Infine `handleClientCommand` abilita il _client_ all'invio di specifici comandi che determinano la modifica dello stato dell'applicazione con conseguente generazione di nuovi eventi. Inoltre, essendo gli _stream_ di `ZIO` di tipo _pull based_, è sufficiente che il _client_ interrompa la comunicazione per impedire al flusso associato di emettere nuovi dati.

![Modello dei messaggi di dominio.\label{messages}](https://raw.githubusercontent.com/amarfoglia/PPS-Project-ZIO/main/report/sections/img/messages.png "Modello dei messaggi di dominio.")

Scendendo a un livello più implementativo, per favorire una maggiore separazione delle responsabilità, le funzionalità sono state suddivise in tre interfacce distinte: 

- `Auth`: implementa la registrazione di un nuovo utente tramite _username_;
- `Chat`: definisce la logica relativa alla gestione dei messaggi, intercettando quelli in entrata al fine di renderli fruibili dagli utenti di una stessa stanza;
- `Lobby`: si occupa di tutte le informazioni che ruotano intorno al concetto di stanza, come l'ingresso e l'uscita di un utente.

In ottica `ZIO`, le tre interfacce e lo stesso `Controller`, possono essere visti come servizi. La loro definizione segue le logiche del _Service Pattern_, cioè:

1. le funzionalità sono circoscritte all'interno di un'apposita interfaccia (_trait_);
2. l'interfaccia viene implementata tramite una specifica classe, il cui costruttore esplicita le dipendenze;
3. il costruttore viene elevato ad uno `ZLayer`.

```scala
// Service Implementation and Dependencies
class ControllerLive(
  val auth: Auth, 
  val chat: Chat, 
  val lobby: Lobby,
) extends Controller: 
  ...

// ZLayer (lifting)   
object Controller:
  val live: URLayer[Auth & Chat & Lobby, Controller] = 
    ZLayer.scoped { 
      for
        auth <- ZIO.service[Auth]
        chat <- ZIO.service[Chat]
        room <- ZIO.service[Room]
      yield ControllerLive(auth, chat, room)
    }
```

Siccome il tipo `ZLayer` è analogo a una funzione `RIn => Either[E, ROut]`, questo può essere composto sia verticalmente che orizzontalmente con altri _layer_. Infatti l'output degli `ZLayer` di `Auth`, `Chat`, `Lobby` viene composto in uno più "complesso" (composizione orizzontale), e passato come input, cioè dipendenza, del _layer_ di `Controller` (composizione verticale).

L'ultimo "strato" dell'applicativo è il servizio denominato `Server`, il quale implementa concretamente i canali di comunicazione con i _client_ sfruttando apposite _Websocket_. Per lo sviluppo del servizio si è adoperata la libreria `zio-http`. Questa ha permesso di costruire agilmente un sever Http che realizza una rotta per la registrazione di un nuovo utente, e due _Websocket_ a supporto degli _stream_ di dati. 

```scala
val app: Http[Controller, Throwable, Request, Response] =
  Http.collectZIO[Request] {
    case req@(Method.POST -> !! / "signup")  => for 
      body <- req.body.asString
      user <- Controller.signup(body)
    yield Response.json(user.toJson)

    case Method.GET  -> !! / "subscribe" => 
      Response.fromSocketApp(commandSocket)

    case Method.GET  -> !! / "subscribeRoom" => 
      Response.fromSocketApp(dataSocket)
        .provideSome[Controller](Scope.default)
  } @@ Middleware.cors(config)
```

A livello implementativo, il comportamento delle _WebSocket_ è stato definito tramite le funzioni `userCommandSocket` e `dataStreamSocket`. Queste si avvalgono di un `commandFilter` per convertire le risorse `json` in specifici eventi di dominio, il quale a sua volta si appoggia sulle funzionalità offerte da `zio-json`.
```scala
def userCommandSocket = commandFilter >>>
  Http.collectZIO[(Channel[WebSocketFrame], ClientCommand)] {
    case (ch, Subscribe) => 
      Controller.subscribe
        .map(_.toJson)
        .map(WebSocketFrame.Text(_))
        .runForeach(ch.writeAndFlush(_))
    case (ch, command: ClientActionCommand) => 
      Controller.handleClientCommand(command).runDrain
  }

def dataStreamSocket = commandFilter >>>
  Http.collectZIO[(Channel[WebSocketFrame], ClientCommand)] {
    case (ch, SubscribeRoom(roomId)) => 
      Controller.subscribeRoom(roomId)
        .map(_.toJson)
        .map(WebSocketFrame.Text(_))
        .runForeach(ch.writeAndFlush(_))    
  }
```
Gli `ZStream` vengono consumati sfruttando l'operatore `runForeach`, il quale presa una funzione in input, ritorna degli oggetti `ZIO` che descrivono il _side effect_ prodotto dalla funzione applicata su ciascun elemento emesso dallo _stream_.

Infine all'interno del `Main` vi è la descrizione del programma, cioè la creazione e l'avvio del _server_, e la risoluzione delle dipendenze. Quest'ultima si realizza passando al metodo `provide` le implementazioni dei servizi richiesti.

```scala
object Main extends ZIOAppDefault:
  val program = for
    _ <- Console.printLine("Starting server")
    _ <- Server.start(8091, app)
  yield ExitCode.success    

  val run = program.provide(
    Controller.live,
    Auth.live,
    Lobby.live,
    Chat.live,
    Repository.inMemory[RoomId, Room](Config(path = "rooms.json")),
    Repository.inMemory[UserId, User](Config(path = "users.json")),
  ) *> ZIO.never
```

### Gestione della concorrenza

Siccome il server è in grado di comunicare con diversi _client_, le interazioni sono state regolate mediante le strutture concorrenti offerte da `ZIO`, al fine di evitare la presenza di stati inconsistenti. Nel caso specifico della gestione dei messaggi, il servizio `Chat` è stato dotato di un campo `messageHubs`, cioè di una `Ref` che mantiene in memoria un `Hub` per ciascuna stanza. La `Ref` esclude _race conditions_ durante l'aggiunta e/o rimozione di stanze, mentre l'`Hub` permette di notificare a tutti gli interessati l'arrivo di nuovi messaggi (strategia _broadcast_).
```scala
case class ChatLive(
  val roomRepository: RoomRepository,
  val messageHubs: Ref.Synchronized[Map[RoomId, Hub[Message]]]
) extends Chat:
  // ...

  override def publishMessage(
    roomId: RoomId, 
    owner: User,
    message: String
  ): Stream[NoSuchElementException, Unit] = 
    ZStream.fromZIO(
      for
        uuid <- Random.nextUUID.map(UUID.fromJavaUUID)
        msg  <- ZIO.succeed(Message.Text(uuid, owner, message))
        hub  <- getOrCreateHub(roomId)
        _    <- hub.offer(msg)
      yield ZIO.unit
    )

  override def roomMessages(
    roomId: RoomId
  ): ZStream[Scope, NoSuchElementException, Message] = 
    for
      hub   <- ZStream.fromZIO(getOrCreateHub(roomId))
      queue <- ZStream.fromZIO(hub.subscribe)
      msg   <- ZStream.fromQueue(queue)
    yield msg
```
L'espressione `hub.offer(msg)` del metodo `publishMessage` aggiorna lo stato dell'`Hub` interessato causando la generazione di un nuovo evento all'interno dello `ZStream` dei messaggi. Questo viene creato a partire dalla coda restituita dall'istruzione `hub.subscribe` della funzione `roomMessages`.

## Frontend

Come già anticipato, il `frontend` è stato sviluppato in `Scala.js`, nel dettaglio si è ricorso alla libreria [`scalajs-dom`](https://scala-js.github.io/scala-js-dom/) al fine di interagire con gli elementi del DOM sfruttando un'interfaccia tipizzata staticamente e fruibile direttamente da codice `Scala`. Similmente al `backend`, anche all'interno del `frontend` vi è la creazione di due _WebSocket_ distinte. In questo caso però non è stato possibile avvalersi del supporto di `zio-http` poiché non interoperabile con `Scala.js`, di conseguenza si è optato per l'API offerta da `scalajs-dom`. 

Infine, lo sviluppo della UI è stato facilitato dalla libreria [`scalatags`](https://com-lihaoyi.github.io/scalatags/), la quale ha permesso di definire programmaticamente alcuni componenti HTML agevolando l'integrazione di questi con la logica di business.

![UI della demo.\label{gui}](https://raw.githubusercontent.com/amarfoglia/PPS-Project-ZIO/main/report/sections/img/gui.png "UI della demo.")
