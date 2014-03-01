spray-websocket
===============

WebSocket for spray-can

* Support java.util.zip based per-frame-deflate
* Pass all Autobahn test cases (both ws and wss)
   * [Autobahn test reports for server (ws)](http://wandoulabs.github.io/spray-websocket/autobahn-reports/ws/servers/index.html)
   * [Autobahn test reports for server (wss)](http://wandoulabs.github.io/spray-websocket/autobahn-reports/wss/servers/index.html)
   * [Autobahn test reports for client (ws)](http://wandoulabs.github.io/spray-websocket/autobahn-reports/ws/clients/index.html)
   * [Autobahn test reports for client (wss)](http://wandoulabs.github.io/spray-websocket/autobahn-reports/wss/clients/index.html)

Example:


The WebSocketConnection which could be extended to your WebSocketWorker by overriding businessLogic

```scala

package spray.can.websocket

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket

trait WebSocketConnection extends Actor with ActorLogging {
  /**
   * The HttpServerConnection actor, which holds the pipelines
   */
  def serverConnection: ActorRef

  def receive = handshaking orElse closeLogic

  def closeLogic: Receive = {
    case x: Http.ConnectionClosed =>
      context.stop(self)
      log.debug("Connection closed: {}, {} stopped.", x, self)
  }

  def handshaking: Receive = {

    // when a client request for upgrading to websocket comes in, we send
    // UHttp.Upgrade to upgrade to websocket pipelines with an accepting response.
    case websocket.HandshakeRequest(state) =>
      state match {
        case wsFailure: websocket.HandshakeFailure => sender() ! wsFailure.response
        case wsContext: websocket.HandshakeContext => sender() ! UHttp.Upgrade(websocket.pipelineStage(self, wsContext), wsContext)
      }

    // upgraded successfully
    case UHttp.Upgraded(wsContext) =>
      log.debug("{} upgraded to WebSocket.", self)
      context.become(businessLogic orElse closeLogic)
  }

  def businessLogic: Receive

}

```


A simple example:

```scala

package spray.can.websocket.examples

import akka.actor.{ ActorSystem, Actor, Props, ActorLogging, ActorRef }
import akka.io.IO
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.{ BinaryFrame, TextFrame }
import spray.http.HttpRequest

object SimpleServer extends App with MySslConfiguration {

  class WebSocketServer extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register a WebSocketConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(Props(classOf[WebSocketWorker], serverConnection))
        serverConnection ! Http.Register(conn)
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


```