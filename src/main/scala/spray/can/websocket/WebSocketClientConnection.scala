package spray.can.websocket

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.http.HttpRequest

trait WebSocketClientConnection extends Actor with ActorLogging {
  def upgradeRequest: HttpRequest

  private var _connection: ActorRef = _
  def connection = _connection

  def receive = handshaking orElse closeLogic

  def closeLogic: Receive = {
    case ev: Http.ConnectionClosed =>
      context.stop(self)
      log.debug("Connection closed on event: {}, {} stopped.", ev, self)
  }

  def handshaking: Receive = {
    case _: Http.Connected =>
      _connection = sender()
      connection ! UHttp.UpgradeRequest(upgradeRequest)

    case resp @ websocket.HandshakeResponse(state) =>
      state match {
        case wsFailure: websocket.HandshakeFailure =>
        case wsContext: websocket.HandshakeContext => sender() ! UHttp.UpgradeClient(websocket.clientPipelineStage(self, wsContext), resp)
      }

    case UHttp.Upgraded =>
      log.debug("{} upgraded to WebSocket.", self)
      context.become(businessLogic orElse closeLogic)
  }

  def businessLogic: Receive

}