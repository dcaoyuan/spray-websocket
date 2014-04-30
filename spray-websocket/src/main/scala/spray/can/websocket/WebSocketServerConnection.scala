package spray.can.websocket

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.Frame
import spray.can.websocket.frame.FrameStream
import spray.routing.HttpServiceActor

trait WebSocketServerConnection extends HttpServiceActor with ActorLogging {
  /**
   * The HttpServerConnection actor, which holds the pipelines
   */
  def serverConnection: ActorRef

  def receive = handshaking orElse businessLogicNoUpgrade orElse closeLogic

  def closeLogic: Receive = {
    case ev: Http.ConnectionClosed =>
      context.stop(self)
      log.debug("Connection closed on event: {}", ev)
  }

  def handshaking: Receive = {

    // when a client request for upgrading to websocket comes in, we send
    // UHttp.Upgrade to upgrade to websocket pipelines with an accepting response.
    case websocket.HandshakeRequest(state) =>
      state match {
        case wsFailure: websocket.HandshakeFailure => sender() ! wsFailure.response
        case wsContext: websocket.HandshakeContext => sender() ! UHttp.UpgradeServer(websocket.pipelineStage(self, wsContext), wsContext.response)
      }

    // upgraded successfully
    case UHttp.Upgraded =>
      context.become(businessLogic orElse closeLogic)
  }

  def businessLogic: Receive

  def businessLogicNoUpgrade: Receive = PartialFunction.empty

  def send(frame: Frame) {
    serverConnection ! FrameCommand(frame)
  }

  def send(frame: FrameStream) {
    serverConnection ! FrameStreamCommand(frame)
  }

}
