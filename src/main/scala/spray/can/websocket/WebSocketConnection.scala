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
        case wsContext: websocket.HandshakeContext => sender() ! UHttp.Upgrade(websocket.pipelineStage(self, wsContext), wsContext.response)
      }

    // upgraded successfully
    case UHttp.Upgraded =>
      log.debug("{} upgraded to WebSocket.", self)
      context.become(businessLogic orElse closeLogic)
  }

  def businessLogic: Receive

}
