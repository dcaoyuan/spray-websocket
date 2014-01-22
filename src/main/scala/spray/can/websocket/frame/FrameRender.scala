package spray.can.websocket.frame

import akka.util.ByteString

object FrameRender {
  import Frame._

  def render(frame: Frame, maskingKey: Array[Byte] = Array.empty): ByteString = {
    import frame._

    val builder = ByteString.newBuilder

    val finBit = if (fin) 1 else 0
    val b0 = (finBit << 7) | (rsv << 4) | opcode.value
    builder.putByte(b0.toByte)

    val masked = if (maskingKey.length > 0) 1 else 0
    val payloadLen = payload.length
    val payloadLenBits =
      if (payloadLen <= 125) payloadLen
      else if (payloadLen <= 65535) 126
      else 127
    val b1 = (masked << 7) | payloadLenBits
    builder.putByte(b1.toByte)

    (b1 & 127) match {
      case 126 => builder.putShort(payloadLen)
      case 127 => builder.putLong(payloadLen)
      case _   =>
    }

    if (masked == 1) {
      builder.putBytes(maskingKey)
      builder.append(maskData(payload, maskingKey))
    } else {
      builder.append(payload)
    }

    builder.result
  }

  def chunkRender(payload: ByteString, fragSize: Int, maskingKey: Array[Byte]) {

  }

}
