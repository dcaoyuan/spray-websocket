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

      // accept client request for upgrading to websocket
      case req @ WebSocket.UpgradeRequest(header) =>
        log.info("Got websocket upgrade req: {}", req)
        sender ! UHttp.Upgrade(WebSocket.pipelineStage(self), Some(WebSocket.acceptResp(header)))

      // upgraded successfully
      case UHttp.Upgraded =>
        log.info("Http Upgraded!")

      // just send back frames for Autobahn test suite
      case x @ (_: BinaryFrame | _: TextFrame) =>
        //log.info("got {}", x)
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