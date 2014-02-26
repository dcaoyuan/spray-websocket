package spray.can.websocket

/**
 * Auto reply incoming pings with pongs
 */
import spray.can.websocket.frame.Opcode
import spray.can.websocket.frame.PingFrame
import spray.io.PipelineContext
import spray.io.Pipelines
import spray.io.PipelineStage

object AutoPong extends PipelineStage {
  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines = new Pipelines {

    val commandPipeline = commandPL

    val eventPipeline: EPL = {
      case FrameInEvent(x: PingFrame) =>
        commandPL(FrameCommand(x.copy(opcode = Opcode.Pong)))

      case ev => eventPL(ev)
    }
  }
}
