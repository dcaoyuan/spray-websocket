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

  def toFinRsvOp(fin: Boolean, opcode: Opcode, rsv1: Boolean = false, rsv2: Boolean = false, rsv3: Boolean = false): Byte = (
    (if (fin) 0x80 else 0x00)
    | (if (rsv1) 0x40 else 0x00)
    | (if (rsv2) 0x20 else 0x00)
    | (if (rsv3) 0x10 else 0x00)
    | (opcode.code & 0x0f)).toByte

  def finFrom(finRsvOp: Byte): Boolean = (finRsvOp & 0x80) != 0
  def rsv1From(finRsvOp: Byte): Boolean = (finRsvOp & 0x40) != 0
  def rsv2From(finRsvOp: Byte): Boolean = (finRsvOp & 0x20) != 0
  def rsv3From(finRsvOp: Byte): Boolean = (finRsvOp & 0x10) != 0
  def opcodeFrom(finRsvOp: Byte): Opcode = Opcode((finRsvOp & 0x0f).toByte)

  def rsvFrom(finRsvOp: Byte): Byte = ((finRsvOp >> 4) & 7).toByte

  /**
   * Note fin should be true for control frames.
   */
  private[frame] def apply(finRsvOp: Byte, payload: ByteString) = opcodeFrom(finRsvOp) match {
    case Opcode.Continuation => ContinuationFrame(finRsvOp, payload)
    case Opcode.Binary       => BinaryFrame(finRsvOp, payload)
    case Opcode.Text         => TextFrame(finRsvOp, payload)
    case Opcode.Close        => CloseFrame(finRsvOp, payload)
    case Opcode.Ping         => PingFrame(finRsvOp, payload)
    case Opcode.Pong         => PongFrame(finRsvOp, payload)
  }

  def unapply(x: Frame): Option[(Boolean, Opcode, ByteString)] =
    Some(x.fin, x.opcode, x.payload)
}

abstract class Frame(_finRsvOp: Byte, _payload: ByteString) extends Serializable {
  import Frame._

  def finRsvOp: Byte = _finRsvOp
  def payload: ByteString = _payload

  def fin: Boolean = finFrom(finRsvOp)
  def rsv1: Boolean = rsv1From(finRsvOp)
  def rsv2: Boolean = rsv2From(finRsvOp)
  def rsv3: Boolean = rsv3From(finRsvOp)
  def opcode: Opcode = opcodeFrom(finRsvOp)

  def rsv: Byte = rsvFrom(finRsvOp)

  def isControl = opcode.isControl
  def isData = !isControl

  def copy(fin: Boolean = this.fin,
           rsv1: Boolean = this.rsv1,
           rsv2: Boolean = this.rsv2,
           rsv3: Boolean = this.rsv3,
           opcode: Opcode = this.opcode,
           payload: ByteString = this.payload) =
    Frame(toFinRsvOp(fin, opcode, rsv1, rsv2, rsv3), payload)

  override def equals(other: Any) = other match {
    case x: Frame => x.finRsvOp == finRsvOp && x.payload == payload
    case _        => false
  }
}

sealed trait FrameStream extends Closeable {
  def opcode: Opcode
  def chunkSize: Int
  def payload: InputStream

  def close {
    if (payload != null) {
      try {
        payload.close()
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
  def apply(payload: ByteString): ContinuationFrame = apply(false, payload)
  def apply(fin: Boolean, payload: ByteString): ContinuationFrame = apply(Frame.toFinRsvOp(fin, Opcode.Continuation), payload)
  def apply(finRsvOp: Byte, payload: ByteString): ContinuationFrame = new ContinuationFrame(finRsvOp, payload)

  def unapply(x: ContinuationFrame): Option[ByteString] = Some(x.payload)
}

final class ContinuationFrame(_finRsvOp: Byte, _payload: ByteString) extends Frame(_finRsvOp, _payload)

/**
 * Binary frame
 */
object BinaryFrame {
  def apply(payload: ByteString): BinaryFrame = apply(true, payload)
  def apply(fin: Boolean, payload: ByteString): BinaryFrame = apply(Frame.toFinRsvOp(fin, Opcode.Binary), payload)
  def apply(finRsvOp: Byte, payload: ByteString): BinaryFrame = new BinaryFrame(finRsvOp, payload)

  def unapply(x: BinaryFrame): Option[ByteString] = Some(x.payload)
}

final class BinaryFrame(_finRsvOp: Byte, _payload: ByteString) extends Frame(_finRsvOp, _payload)

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
  def apply(text: String): TextFrame = apply(true, ByteString(text))
  def apply(payload: ByteString): TextFrame = apply(true, payload)
  def apply(fin: Boolean, payload: ByteString): TextFrame = apply(Frame.toFinRsvOp(fin, Opcode.Text), payload)
  def apply(finRsvOp: Byte, payload: ByteString): TextFrame = new TextFrame(finRsvOp, payload)

  def unapply(x: TextFrame): Option[ByteString] = Some(x.payload)
}

final class TextFrame(_finRsvOp: Byte, _payload: ByteString) extends Frame(_finRsvOp, _payload) {
  override def toString = _payload.utf8String.take(100)
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
  def apply(statusCode: StatusCode, reason: String = ""): CloseFrame = apply(toPayload(statusCode, reason))
  def apply(payload: ByteString): CloseFrame = apply(Frame.toFinRsvOp(true, Opcode.Close), payload)
  def apply(finRsvOp: Byte, payload: ByteString): CloseFrame = new CloseFrame(finRsvOp, payload)

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

final class CloseFrame(_finRsvOp: Byte, _payload: ByteString) extends Frame(_finRsvOp, _payload)
/**
 * Ping frame
 */
object PingFrame {
  def apply(): PingFrame = apply(ByteString.empty)
  def apply(payload: ByteString): PingFrame = apply(Frame.toFinRsvOp(true, Opcode.Ping), payload)
  def apply(finRsvOp: Byte, payload: ByteString): PingFrame = new PingFrame(finRsvOp, payload)

  def unapply(x: PingFrame): Option[ByteString] = Some(x.payload)
}

final class PingFrame(_finRsvOp: Byte, _payload: ByteString) extends Frame(_finRsvOp, _payload)

/**
 * Pong frame
 */
object PongFrame {
  def apply(): PongFrame = apply(ByteString.empty)
  def apply(payload: ByteString): PongFrame = apply(Frame.toFinRsvOp(true, Opcode.Pong), payload)
  def apply(finRsvOp: Byte, payload: ByteString): PongFrame = new PongFrame(finRsvOp, payload)

  def unapply(x: PongFrame): Option[ByteString] = Some(x.payload)
}

final class PongFrame(_finRsvOp: Byte, _payload: ByteString) extends Frame(_finRsvOp, _payload)
