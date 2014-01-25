package spray.can.websocket

import akka.io.Tcp
import spray.can.websocket.frame.FrameRender
import spray.can.websocket.frame.Opcode
import spray.io.PipelineContext
import spray.io.Pipelines
import spray.io.PipelineStage

object FrameRendering {

  def apply(maskingKeyGen: Option[() => Array[Byte]]) = new PipelineStage {
    def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines = new Pipelines {
      def maskingKey = maskingKeyGen.fold(Array.empty[Byte])(_())

      val commandPipeline: CPL = {
        case FrameCommand(frame) =>
          commandPL(Tcp.Write(FrameRender.render(frame, maskingKey)))
          if (frame.opcode == Opcode.Close) {
            commandPL(Tcp.Close)
          }
        case FrameStreamCommand(frameStream) => FrameRender.streamingRender(frameStream).foreach(f => commandPL(Tcp.Write(FrameRender.render(f, maskingKey))))
            frameStream.close
        case cmd                       => commandPL(cmd)
      }

      val eventPipeline = eventPL
    }

  }
}
