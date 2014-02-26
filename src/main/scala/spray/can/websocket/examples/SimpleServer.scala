package spray.can.websocket.examples

import akka.actor.{ ActorSystem, Actor, Props, ActorLogging, ActorRef }
import akka.io.IO
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.{ PingFrame, PongFrame, BinaryFrame, TextFrame }
import spray.http.HttpRequest

object SimpleServer extends App with MySslConfiguration {

  class WebSocketServer extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register a WebSocketConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(Props(classOf[WebSocketWorker], serverConnection))
        serverConnection ! Http.Register(conn)
      case PingFrame(payload) =>
        sender() ! PongFrame(payload)
    }
  }

  class WebSocketWorker(val serverConnection: ActorRef) extends websocket.WebSocketConnection {
    def businessLogic: Receive = {
      // just bounce frames back for Autobahn testsuite
      case x @ (_: BinaryFrame | _: TextFrame) =>
        sender() ! x

      case x: HttpRequest => // do something
    }
  }

  implicit val system = ActorSystem()
  import system.dispatcher

  val server = system.actorOf(Props(classOf[WebSocketServer]), "websocket")

  IO(UHttp) ! Http.Bind(server, "localhost", 8080)

  readLine("Hit ENTER to exit ...\n")
  system.shutdown()
  system.awaitTermination()
}
