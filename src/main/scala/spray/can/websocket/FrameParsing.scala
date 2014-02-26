package spray.can.websocket

import akka.io.Tcp
import spray.can.websocket.frame.StatusCode
import spray.can.websocket.frame.CloseFrame
import spray.can.websocket.frame.FrameParser
import spray.io.PipelineContext
import spray.io.PipelineStage
import spray.io.Pipelines

object FrameParsing {

  def apply(frameSizeLimit: Int) = new PipelineStage {
    FrameParser.frameSizeLimit = frameSizeLimit
    def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines = new Pipelines {
      val parser = new FrameParser()

      val commandPipeline: CPL = commandPL

      val eventPipeline: EPL = {
        case Tcp.Received(data) =>
          parser.onReceive(data.iterator) {
            case FrameParser.Success(frame)        => eventPL(FrameInEvent(frame))
            case FrameParser.Failure(code, reason) => closeWithReason(code, reason)
          }
        case ev => eventPL(ev)
      }

      def closeWithReason(statusCode: StatusCode, reason: String = "") = {
        context.log.debug("To close with statusCode: {}, reason: {}", statusCode, reason)
        commandPL(FrameCommand(CloseFrame(statusCode, reason)))
      }

    }

  }
}
