spray-websocket
===============

WebSocket for spray-can

Example:

```scala
object SimpleServer extends App with MySslConfiguration {

  class SocketServer extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register ourselves as the connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        log.info("Connected to HttpListener: {}", sender.path)
        sender ! Http.Register(self)

      // when a client request for upgrading to websocket comes in, we send
      // UHttp.Upgrade to upgrade to websocket pipelines with an accepting response.
      case WebSocket.UpgradeRequest(header) =>
        sender ! UHttp.Upgrade(WebSocket.pipelineStage(self), Some(WebSocket.acceptResp(header)))

      // upgraded successfully
      case UHttp.Upgraded =>
        log.info("Http Upgraded!")

      // just bounce frames back for Autobahn testsuite
      case x @ (_: BinaryFrame | _: TextFrame) =>
        sender ! x

      case x: Frame =>
      //log.info("Got frame: {}", x)

    }
  }

  implicit val system = ActorSystem()
  import system.dispatcher

  val worker = system.actorOf(Props(classOf[SocketServer]), "websocket")

  IO(UHttp) ! Http.Bind(
    worker,
    "localhost",
    8080)

  readLine("Hit ENTER to exit ...\n")
  system.shutdown()
  system.awaitTermination()
}
```