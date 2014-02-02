package spray.can.websocket.frame

import akka.util.ByteString
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.io.{ Closeable, InputStream }
import spray.can.websocket.UTF8Validator

/**
 *
 * http://tools.ietf.org/html/rfc6455
 */
object Frame {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN
  val UTF8 = Charset.forName("UTF-8")

  /**
   * Octet i of the transformed data ("transformed-octet-i") is the XOR of
   * octet i of the original data ("original-octet-i") with octet at index
   * i modulo 4 of the masking key ("masking-key-octet-j"):
   *
   * j                   = i MOD 4
   * transformed-octet-i = original-octet-i XOR masking-key-octet-j
   */
  def maskData(data: Array[Byte], maskingKey: Array[Byte]): Array[Byte] = {
    var i = 0
    while (i < data.length) {
      data(i) = (data(i) ^ (maskingKey(i % 4))).toByte
      i += 1
    }
    data
  }

  def maskData(data: ByteString, maskingKey: Array[Byte]): ByteString = {
    val masked = Array.ofDim[Byte](data.length)
    var i = 0
    while (i < data.length) {
      masked(i) = (data(i) ^ (maskingKey(i % 4))).toByte
      i += 1
    }
    ByteString(masked)
  }

  /**
   * Note fin should be true for control frames.
   */
  private[frame] def apply(fin: Boolean, rsv: Byte, opcode: Opcode, payload: ByteString) = opcode match {
    case Opcode.Continuation => ContinuationFrame(fin, rsv, payload)
    case Opcode.Binary       => BinaryFrame(fin, rsv, payload)
    case Opcode.Text         => TextFrame(fin, rsv, payload)
    case Opcode.Close        => CloseFrame(rsv, payload)
    case Opcode.Ping         => PingFrame(rsv, payload)
    case Opcode.Pong         => PongFrame(rsv, payload)
  }

  def unapply(x: Frame): Option[(Boolean, Byte, Opcode, ByteString)] =
    Some(x.fin, x.rsv, x.opcode, x.payload)
}

sealed trait Frame {
  def fin: Boolean
  def rsv: Byte
  def opcode: Opcode
  def payload: ByteString

  def copy(fin: Boolean = this.fin,
           rsv: Byte = this.rsv,
           opcode: Opcode = this.opcode,
           payload: ByteString = this.payload) =
    Frame(fin, rsv, opcode, payload)

  def isControl = opcode.isControl
}

sealed trait FrameStream extends Closeable {
  def opcode: Opcode
  def chunkSize: Int
  def payload: InputStream

  def close {
    if (payload != null) {
      try {
        payload.close
      } catch {
        case _: Throwable =>
      }
    }
  }
}

object ControlFrame {
  def unapply(x: Frame): Option[(Boolean, Opcode, ByteString)] = {
    if (x.opcode.isControl) Some(x.fin, x.opcode, x.payload)
    else None
  }
}

object DataFrame {
  def unapply(x: Frame): Option[(Boolean, Opcode, ByteString)] = {
    if (!x.opcode.isControl) Some(x.fin, x.opcode, x.payload)
    else None
  }
}

/**
 * Binary frame
 */
object ContinuationFrame {
  def apply(payload: ByteString): ContinuationFrame = apply(false, 0, payload)
  def apply(fin: Boolean, payload: ByteString): ContinuationFrame = apply(fin, 0, payload)
  def apply(fin: Boolean, rsv: Byte, payload: ByteString): ContinuationFrame = new ContinuationFrame(fin, rsv, payload)

  def unapply(x: ContinuationFrame): Option[ByteString] = Some(x.payload)
}

final class ContinuationFrame(val fin: Boolean, val rsv: Byte, val payload: ByteString) extends Frame {
  def opcode = Opcode.Continuation
}

/**
 * Binary frame
 */
object BinaryFrame {
  def apply(payload: ByteString): BinaryFrame = apply(true, 0, payload)
  def apply(fin: Boolean, payload: ByteString): BinaryFrame = apply(fin, 0, payload)
  def apply(fin: Boolean, rsv: Byte, payload: ByteString): BinaryFrame = new BinaryFrame(fin, rsv, payload)

  def unapply(x: BinaryFrame): Option[ByteString] = Some(x.payload)
}

final class BinaryFrame(val fin: Boolean, val rsv: Byte, val payload: ByteString) extends Frame {
  def opcode = Opcode.Binary
}

object BinaryFrameStream {
  def apply(payload: InputStream): BinaryFrameStream = BinaryFrameStream(payload)
}

final case class BinaryFrameStream(chunkSize: Int, payload: InputStream) extends FrameStream {
  def opcode = Opcode.Binary
}

/**
 * Text frame
 */
object TextFrame {
  def apply(payload: ByteString): TextFrame = apply(true, 0, payload)
  def apply(fin: Boolean, payload: ByteString): TextFrame = apply(fin, 0, payload)
  def apply(fin: Boolean, rsv: Byte, payload: ByteString): TextFrame = new TextFrame(fin, rsv, payload)

  def unapply(x: TextFrame): Option[ByteString] = Some(x.payload)
}

final class TextFrame(val fin: Boolean, val rsv: Byte, val payload: ByteString) extends Frame {
  def opcode = Opcode.Text
}

object TextFrameStream {
  def apply(payload: InputStream): TextFrameStream = TextFrameStream(payload)
}

final case class TextFrameStream(chunkSize: Int, payload: InputStream) extends FrameStream {
  def opcode = Opcode.Text
}

/**
 * Close frame
 */
object CloseFrame {
  def apply(): CloseFrame = apply(StatusCode.NormalClose)
  def apply(statusCode: StatusCode, reason: String = ""): CloseFrame = apply(0.toByte, toPayload(statusCode, reason))
  def apply(payload: ByteString): CloseFrame = apply(0.toByte, payload)
  def apply(rsv: Byte, payload: ByteString): CloseFrame = new CloseFrame(rsv, payload)

  def unapply(x: CloseFrame): Option[(StatusCode, String)] = {
    x.payload.length match {
      case 0 => Some(StatusCode.NormalClose, "Normal close of connection.")
      case 1 => Some(StatusCode.ProtocolError, "Received illegal close frame with payload length is 1, the length should be 0 or at least 2.")
      case _ =>
        val (codex, reasonx) = x.payload.splitAt(2)
        val code = codex.iterator.getShort(Frame.byteOrder)
        if (StatusCode.notAllowed(code)) {
          Some(StatusCode.ProtocolError, "Received illegal close code " + code)
        } else {
          if (UTF8Validator.isInvalid(reasonx)) {
            Some(StatusCode.ProtocolError, "non-UTF-8 [RFC3629] data within a text message.")
          } else {
            Some(StatusCode(code), reasonx.utf8String)
          }
        }
    }
  }

  private def toPayload(code: StatusCode, reason: String) =
    ByteString(code.toBytes ++ reason.getBytes(Frame.UTF8))
}

final class CloseFrame(val rsv: Byte, val payload: ByteString) extends Frame {
  def fin = true
  def opcode = Opcode.Close
}

/**
 * Ping frame
 */
object PingFrame {
  def apply(): PingFrame = apply(0, ByteString.empty)
  def apply(payload: ByteString): PingFrame = apply(0, payload)
  def apply(rsv: Byte, payload: ByteString): PingFrame = new PingFrame(rsv, payload)

  def unapply(x: PingFrame): Option[ByteString] = Some(x.payload)
}

final class PingFrame(val rsv: Byte, val payload: ByteString) extends Frame {
  def fin = true
  def opcode = Opcode.Ping
}

/**
 * Pong frame
 */
object PongFrame {
  def apply(): PongFrame = apply(0, ByteString.empty)
  def apply(payload: ByteString): PongFrame = apply(0, payload)
  def apply(rsv: Byte, payload: ByteString): PongFrame = new PongFrame(rsv, payload)

  def unapply(x: PongFrame): Option[ByteString] = Some(x.payload)
}

final class PongFrame(val rsv: Byte, val payload: ByteString) extends Frame {
  def fin = true
  def opcode = Opcode.Pong
}
