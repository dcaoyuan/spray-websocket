package spray.can.websocket

import akka.io.Tcp
import spray.can.websocket.frame.StatusCode
import spray.can.websocket.frame.CloseFrame
import spray.can.websocket.frame.ContinuationFrame
import spray.can.websocket.frame.Frame
import spray.can.websocket.frame.FrameRender
import spray.can.websocket.frame.Opcode
import spray.io.PipelineContext
import spray.io.PipelineStage
import spray.io.Pipelines

object FrameComposing {

  def apply(messageSizeLimit: Long) = new PipelineStage {
    def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines = new Pipelines {

      var infinFrames: List[Frame] = Nil // TODO as an interface that can be adapted to outside cache

      val commandPipeline = commandPL

      val eventPipeline: EPL = {

        case FrameInEvent(x) if x.rsv != 0 =>
          closeWithReason(StatusCode.ProtocolError,
            "RSV MUST be 0 unless an extension is negotiated that defines meanings for non-zero values.")
          infinFrames = Nil

        case FrameInEvent(x @ Frame(false, _, Opcode.Text | Opcode.Binary, _)) =>
          infinFrames match {
            case Nil =>
              infinFrames = x :: Nil
            case _ =>
              closeWithReason(StatusCode.ProtocolError,
                "Expect a continuation frame, but received a text/binary frame.")
              infinFrames = Nil
          }

        case FrameInEvent(x @ ContinuationFrame(false, _, payload)) =>
          infinFrames match {
            case Nil =>
              closeWithReason(StatusCode.ProtocolError,
                "Received a continuation frame, but without previous fragment frame(s).")
            case _ =>
              infinFrames = x :: infinFrames
          }

        case FrameInEvent(x @ Frame(true, _, Opcode.Continuation | Opcode.Text | Opcode.Binary, payload)) =>
          if (x.opcode == Opcode.Continuation && infinFrames.isEmpty) {

            closeWithReason(StatusCode.ProtocolError,
              "Received a final continuation frame, but without previous fragment frame(s).")

          } else if (x.opcode != Opcode.Continuation && infinFrames.nonEmpty) {

            closeWithReason(StatusCode.ProtocolError,
              "Received a final text/binary frame, but there has been fragment frame(s) existed and not finished yet.")

          } else if (infinFrames.foldLeft(x.payload.length)(_ + _.payload.length) > messageSizeLimit) {
            closeWithReason(StatusCode.MessageTooBig,
              "Received a message that is too big for it to process, message size should not exceed " + messageSizeLimit)

          } else {

            val head :: tail = (x :: infinFrames).reverse
            val finalFrame = tail.foldLeft(head) { (acc, cont) => acc.copy(payload = acc.payload ++ cont.payload) }
            if (finalFrame.opcode == Opcode.Text && !UTF8Validate.isValidate(finalFrame.payload)) {
              closeWithReason(StatusCode.InvalidFramePayloadData,
                "non-UTF-8 [RFC3629] data within a text message.")
            } else {
              eventPL(FrameInEvent(finalFrame.copy(fin = true, payload = finalFrame.payload.compact)))
            }

          }

          infinFrames = Nil

        case FrameInEvent(Frame(true, _, opcode, payload)) if opcode.isControl && payload.length > 125 =>
          closeWithReason(StatusCode.ProtocolError,
            "All control frames MUST have a payload length of 125 bytes or less and MUST NOT be fragmented.")
          infinFrames = Nil

        case ev @ FrameInEvent(CloseFrame(true, _, payload)) =>
          payload.length match {
            case 0 => closeWithReason(StatusCode.NormalClosure)
            case 1 => closeWithReason(StatusCode.ProtocolError,
              "Received illegal close frame with payload length is 1, the length should be 0 or at least 2.")
            case _ =>
              val (code, reason) = payload.splitAt(2)
              val statusCode = code.iterator.getShort(Frame.byteOrder)
              if (!StatusCode.isValidCloseCode(statusCode)) {
                closeWithReason(StatusCode.ProtocolError,
                  "Received illegal close code " + statusCode)
              } else {
                if (!UTF8Validate.isValidate(reason)) {
                  closeWithReason(StatusCode.ProtocolError,
                    "Closing reason is not UTF-8 encoded.")
                } else {
                  closeWithReason(StatusCode.NormalClosure,
                    reason.utf8String)
                }
              }
          }
          infinFrames = Nil
          eventPL(ev)

        case ev @ FrameInEvent(Frame(true, _, Opcode.Ping | Opcode.Pong, _)) =>
          eventPL(ev)

        case FrameInEvent(_) =>
          closeWithReason(StatusCode.ProtocolError,
            "Closed due to wrong frames order.")
          infinFrames = Nil

        case ev => eventPL(ev)
      }

      /**
       * Clean closes the websocket pipeline with reason
       */
      def closeWithReason(statusCode: StatusCode, reason: String = "") = {
        commandPL(Tcp.Write(FrameRender.render(CloseFrame(statusCode, reason))))
        commandPL(Tcp.Close)
      }
    }
  }

}