package spray.can.websocket.frame

import akka.util.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

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

  def toFrame(fin: Boolean, rsv: Byte, opcode: Opcode, payload: ByteString) = opcode match {
    case Opcode.Continuation => ContinuationFrame(fin, rsv, payload)
    case Opcode.Binary       => BinaryFrame(fin, rsv, payload)
    case Opcode.Text         => TextFrame(fin, rsv, payload)
    case Opcode.Close        => CloseFrame(fin, rsv, payload)
    case Opcode.Ping         => PingFrame(fin, rsv, payload)
    case Opcode.Pong         => PongFrame(fin, rsv, payload)
  }

  def unapply(x: Frame): Option[(Boolean, Byte, Opcode, ByteString)] =
    Some((x.fin, x.rsv, x.opcode, x.payload))
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
    Frame.toFrame(fin, rsv, opcode, payload)

  def isControl = opcode.isControl

  //lazy val stringData = payload.utf8String
}

/**
 * Binary frame
 */
object ContinuationFrame {
  def apply(payload: ByteString): ContinuationFrame = ContinuationFrame(false, 0, payload)
}
case class ContinuationFrame(fin: Boolean, rsv: Byte, payload: ByteString) extends Frame {
  def opcode = Opcode.Continuation
}

/**
 * Binary frame
 */
object BinaryFrame {
  def apply(payload: ByteString): BinaryFrame = BinaryFrame(true, 0, payload)
}
case class BinaryFrame(fin: Boolean, rsv: Byte, payload: ByteString) extends Frame {
  def opcode = Opcode.Binary
}

/**
 * Text frame
 */
object TextFrame {
  def apply(payload: ByteString): TextFrame = TextFrame(true, 0, payload)
}
case class TextFrame(fin: Boolean, rsv: Byte, payload: ByteString) extends Frame {
  def opcode = Opcode.Text
}

/**
 * Close frame
 */
object CloseFrame {
  def apply(statusCode: StatusCode, reason: String = ""): CloseFrame = CloseFrame(true, 0, toCloseFrameData(statusCode, reason))

  private def toCloseFrameData(statusCode: StatusCode, reason: String = ""): ByteString = {
    import Frame._

    val buf = ByteBuffer.allocate(2 + reason.length)
    buf.putShort(statusCode.code)
    if (reason.length > 0) {
      buf.put(reason.getBytes(UTF8))
    }
    ByteString(buf.array)
  }

}
case class CloseFrame(fin: Boolean, rsv: Byte, payload: ByteString) extends Frame {
  def opcode = Opcode.Close
}

/**
 * Ping frame
 */
object PingFrame {
  def apply(): PingFrame = PingFrame(true, 0, ByteString.empty)
  def apply(payload: ByteString): PingFrame = PingFrame(true, 0, payload)
}
case class PingFrame(fin: Boolean, rsv: Byte, payload: ByteString) extends Frame {
  def opcode = Opcode.Ping
}

/**
 * Pong frame
 */
object PongFrame {
  def apply(): PongFrame = PongFrame(true, 0, ByteString.empty)
  def apply(payload: ByteString): PongFrame = PongFrame(true, 0, payload)
}
case class PongFrame(fin: Boolean, rsv: Byte, payload: ByteString) extends Frame {
  def opcode = Opcode.Pong
}
