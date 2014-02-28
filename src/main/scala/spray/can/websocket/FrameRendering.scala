package spray.can.websocket

import akka.io.Tcp
import spray.can.websocket.frame.DataFrame
import spray.can.websocket.frame.FrameRender
import spray.can.websocket.frame.Opcode
import spray.io._

object FrameRendering {

  def apply(maskingKeyGen: Option[() => Array[Byte]], wsContext: HandshakeContext) = new PipelineStage {
    def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines = new Pipelines with DynamicCommandPipeline {
      def maskingKey = maskingKeyGen.fold(Array.empty[Byte])(_())

      def initialCommandPipeline: CPL = {
        case FrameCommand(frame) =>
          val frame1 = frame match {
            case DataFrame(_, _, payload) =>
              wsContext.pmce map { pmce =>
                try {
                  frame.copy(rsv1 = true, payload = pmce.encode(payload))
                } catch {
                  case ex: Throwable => frame // fallback to uncompressed frame
                }
              } getOrElse (frame)
            case _ => frame
          }

          context.log.debug("Writing frame {}", frame1.opcode)
          val isClose = frame1.opcode == Opcode.Close
          if (isClose) {
            commandPipeline.become(waitingForClosing)
          }
          commandPL(Tcp.Write(FrameRender.render(frame1, maskingKey)))
          if (isClose) {
            commandPL(Tcp.Close)
          }

        case FrameStreamCommand(frameStream) =>
          FrameRender.streamingRender(frameStream).foreach(f => commandPL(Tcp.Write(FrameRender.render(f, maskingKey))))
          frameStream.close

        case cmd => commandPL(cmd)
      }

      def waitingForClosing: CPL = {
        case _ => // stop process any commands
      }

      val eventPipeline = eventPL
    }

  }
}
