package spray.can.websocket

import akka.actor.{ Stash, Actor, ActorLogging, ActorRef }
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.can.Http.Connect

trait WebSocketClientWorker extends ActorLogging with Stash { _: Actor =>
  def upgradeRequest: HttpRequest

  private var _connection: ActorRef = _
  /**
   * The actor which could receive frame directly. ie. by
   *   connection ! frame
   */
  def connection = _connection

  def receive = handshaking orElse closeLogic

  def closeLogic: Receive = {
    case ev: Http.ConnectionClosed =>
      context.stop(self)
      log.debug("Connection closed on event: {}", ev)
  }

  def handshaking: Receive = {
    case Http.Connected(remoteAddress, localAddress) =>
      val upgradePipelineStage = { response: HttpResponse =>
        response match {
          case websocket.HandshakeResponse(state) =>
            state match {
              case wsFailure: websocket.HandshakeFailure => None
              case wsContext: websocket.HandshakeContext => Some(websocket.clientPipelineStage(self, wsContext))
            }
        }
      }
      sender() ! UHttp.UpgradeClient(upgradePipelineStage, upgradeRequest)

    case UHttp.Upgraded =>
      // this is the proper actor that could receive frame sent to it directly
      // @see WebSocketFrontend#receiverRef
      _connection = sender()
      context.become(businessLogic orElse closeLogic)
      unstashAll()
      self ! websocket.UpgradedToWebSocket // notify Upgraded to WebSocket protocol, should send to self after unstashAll

    case Http.CommandFailed(con: Connect) =>
      log.warning("failed to connect to {}", con.remoteAddress)
      context.stop(self)

    case cmd @ (_: Send | _: SendStream) =>
      log.debug("stashing cmd {} ", cmd)
      stash()
  }

  def businessLogic: Receive

}
