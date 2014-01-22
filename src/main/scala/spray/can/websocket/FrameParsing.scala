package spray.can.websocket

import akka.io.Tcp
import spray.can.websocket.WebSocket.FrameInEvent
import spray.can.websocket.frame.StatusCode
import spray.can.websocket.frame.CloseFrame
import spray.can.websocket.frame.FrameParser
import spray.can.websocket.frame.FrameRender
import spray.io.PipelineContext
import spray.io.PipelineStage
import spray.io.Pipelines
import spray.io.TickGenerator

object FrameParsing {

  def apply(frameSizeLimit: Int) = new PipelineStage {
    def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines = new Pipelines {
      val parser = new FrameParser(frameSizeLimit)

      val commandPipeline: CPL = commandPL

      val eventPipeline: EPL = {
        case Tcp.Received(data) =>

          parser.onReceive(data.iterator) {
            case FrameParser.Success(frame) =>
              eventPL(FrameInEvent(frame))

            case FrameParser.InvalidOp =>
              closeWithReason(StatusCode.ProtocolError, "Invalid opcode.")

            case FrameParser.Oversized =>
              closeWithReason(StatusCode.MessageTooBig, "Received a message that is too big for it to process, message size should not exceed " + frameSizeLimit)

            case _ => // wait for data next coming, not finished yet
          }

        case ev @ TickGenerator.Tick => eventPL(ev) // TODO timeout here?

        case ev                      => eventPL(ev)
      }

      def closeWithReason(statusCode: StatusCode, reason: String = "") = {
        commandPL(Tcp.Write(FrameRender.render(CloseFrame(statusCode, reason))))
        commandPL(Tcp.Close)
      }

    }

  }
}
