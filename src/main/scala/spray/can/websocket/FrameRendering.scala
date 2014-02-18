package spray.can.websocket

import akka.io.Tcp
import spray.can.websocket.frame.DataFrame
import spray.can.websocket.frame.FrameRender
import spray.can.websocket.frame.Opcode
import spray.io.PipelineContext
import spray.io.Pipelines
import spray.io.PipelineStage

object FrameRendering {

  def apply(maskingKeyGen: Option[() => Array[Byte]], wsContext: HandshakeContext) = new PipelineStage {
    def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines = new Pipelines {
      def maskingKey = maskingKeyGen.fold(Array.empty[Byte])(_())

      val commandPipeline: CPL = {
        case FrameCommand(frame) =>
          val frame1 = frame match {
            case DataFrame(fin, opcode, payload) =>
              wsContext.pmce map { pmce =>
                try {
                  frame.copy(rsv1 = true, payload = pmce.encode(payload))
                } catch {
                  case ex: Throwable => frame // fallback to uncompressed frame
                }
              } getOrElse (frame)
            case _ => frame
          }
          commandPL(Tcp.Write(FrameRender.render(frame1, maskingKey)))
          if (frame1.opcode == Opcode.Close) {
            commandPL(Tcp.Close)
          }

        case FrameStreamCommand(frameStream) =>
          FrameRender.streamingRender(frameStream).foreach(f => commandPL(Tcp.Write(FrameRender.render(f, maskingKey))))
          frameStream.close

        case cmd => commandPL(cmd)
      }

      val eventPipeline = eventPL
    }

  }
}
