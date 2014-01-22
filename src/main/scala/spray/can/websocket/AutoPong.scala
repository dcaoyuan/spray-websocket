package spray.can.websocket

/**
 * Auto reply incoming pings with pongs
 */
import spray.can.websocket.WebSocket.FrameInEvent
import spray.can.websocket.WebSocket.FrameOutEvent
import spray.can.websocket.frame.Opcode
import spray.can.websocket.frame.PingFrame
import spray.io.PipelineContext
import spray.io.Pipelines
import spray.io.PipelineStage

object AutoPong {

  def apply(enabled: Boolean = true) = new PipelineStage {
    def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines = new Pipelines {

      val commandPipeline = commandPL

      val eventPipeline: EPL = {
        case FrameInEvent(x @ PingFrame(true, _, _)) =>
          eventPL(FrameOutEvent(x.copy(opcode = Opcode.Pong)))

        case ev => eventPL(ev)
      }
    }
  }
}