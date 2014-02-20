package spray.can.websocket.examples

import akka.actor.{ ActorSystem, Actor, Props, ActorLogging }
import akka.io.IO
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.BinaryFrame
import spray.can.websocket.frame.TextFrame
import spray.http.HttpRequest

object SimpleServer extends App with MySslConfiguration {

  /**
   * This is actually a singleton actor due to akka IO machanism, we can only
   * identify each client-connection by underlying sender()
   */
  class WebSocketServer extends Actor with ActorLogging {
    def receive = handshaking orElse businessLogic

    def handshaking: Receive = {
      // when a new connection comes in we register ourselves as the connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        sender() ! Http.Register(self)

      // when a client request for upgrading to websocket comes in, we send
      // UHttp.Upgrade to upgrade to websocket pipelines with an accepting response.
      case websocket.HandshakeRequest(state) =>
        state match {
          case wsFailure: websocket.HandshakeFailure => sender() ! wsFailure.response
          case wsContext: websocket.HandshakeContext => sender() ! UHttp.Upgrade(websocket.pipelineStage(self, wsContext), wsContext)
        }

      // upgraded successfully
      case UHttp.Upgraded(wsContext) =>
        log.info("Http Upgraded!")
    }

    def businessLogic: Receive = {
      // just bounce frames back for Autobahn testsuite
      case x @ (_: BinaryFrame | _: TextFrame) =>
        sender() ! x

      case x: HttpRequest => // do something

    }
  }

  implicit val system = ActorSystem()
  import system.dispatcher

  val worker = system.actorOf(Props(classOf[WebSocketServer]), "websocket")

  IO(UHttp) ! Http.Bind(worker, "localhost", 8080)

  readLine("Hit ENTER to exit ...\n")
  system.shutdown()
  system.awaitTermination()
}
