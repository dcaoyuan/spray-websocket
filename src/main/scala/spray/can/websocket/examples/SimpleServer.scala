package spray.can.websocket.examples

import akka.actor.{ ActorSystem, Actor, Props, ActorLogging, ActorRef }
import akka.io.IO
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.{ BinaryFrame, TextFrame }
import spray.http.HttpRequest

object SimpleServer extends App with MySslConfiguration {

  object WebSocketServer {
    def props() = Props(classOf[WebSocketServer])
  }
  class WebSocketServer extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register a WebSocketConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(WebSocketWorker.props(serverConnection))
        serverConnection ! Http.Register(conn)
    }
  }

  object WebSocketWorker {
    def props(serverConnection: ActorRef) = Props(classOf[WebSocketWorker], serverConnection)
  }
  class WebSocketWorker(val serverConnection: ActorRef) extends websocket.WebSocketServerConnection {
    def businessLogic: Receive = {
      // just bounce frames back for Autobahn testsuite
      case x @ (_: BinaryFrame | _: TextFrame) =>
        sender() ! x

      case x: HttpRequest => // do something
    }
  }

  implicit val system = ActorSystem()
  import system.dispatcher

  val server = system.actorOf(WebSocketServer.props(), "websocket")

  IO(UHttp) ! Http.Bind(server, "localhost", 8080)

  readLine("Hit ENTER to exit ...\n")
  system.shutdown()
  system.awaitTermination()
}
