package spray.can.websocket.compress

import akka.util.ByteString
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import spray.http.HttpHeaders

object PermessageDeflate {

  // zlib defalte parameters. TODO can only be supported in zlib(org.jcraft.jzlib) not in java.util.zip
  val WINDOW_SIZE_PERMISSIBLE_VALUES = Set(8, 9, 10, 11, 12, 13, 14, 15)
  val DEFAULT_WBITS = 15
  val DEFAULT_MEM_LEVEL = 8

  val SERVER_MAX_WINDOW_BITS = "c2s_no_context_takeover" // "server_max_window_bits"
  val CLIENT_MAX_WINDOW_BITS = "s2c_no_context_takeover" // "client_max_window_bits"
  val SERVER_NO_CONTEXT_TAKEOVER = "server_no_context_takeover"
  val CLIENT_NO_CONTEXT_TAKEOVER = "client_no_context_takeover"

  val WBITS_NOT_SET = 0
  val TAIL = Array[Byte](0x00, 0x00, 0xFF.toByte, 0xFF.toByte)
  val OVERHEAD = 64

  val BFINAL_HACK = false // TODO

  def apply(params: Map[String, String]): PermessageDeflate = PermessageDeflate(
    server_max_window_bits = params.get(SERVER_MAX_WINDOW_BITS) match {
      case Some(x) =>
        try {
          val v = x.toInt
          if (WINDOW_SIZE_PERMISSIBLE_VALUES.contains(v)) v else DEFAULT_WBITS
        } catch {
          case _: Throwable => DEFAULT_WBITS
        }
      case None => DEFAULT_WBITS
    },

    client_max_window_bits = params.get(CLIENT_MAX_WINDOW_BITS) match {
      case Some(x) =>
        try {
          val v = x.toInt
          if (PermessageDeflate.WINDOW_SIZE_PERMISSIBLE_VALUES.contains(v)) v else DEFAULT_WBITS
        } catch {
          case _: Throwable => DEFAULT_WBITS
        }
      case None => DEFAULT_WBITS
    },

    server_no_context_takeover = params.get(SERVER_NO_CONTEXT_TAKEOVER).isDefined,
    client_no_context_takeover = params.get(CLIENT_NO_CONTEXT_TAKEOVER).isDefined)
}

/**
 *
 * Context Takeover Control.
 * If true, the same LZ77 window is used between messages. Can be overridden with extension parameters.
 */
final case class PermessageDeflate(
  isServer: Boolean = true,
  server_no_context_takeover: Boolean = false,
  client_no_context_takeover: Boolean = false,
  server_max_window_bits: Int = PermessageDeflate.WBITS_NOT_SET,
  client_max_window_bits: Int = PermessageDeflate.WBITS_NOT_SET,
  mem_level: Int = PermessageDeflate.DEFAULT_MEM_LEVEL) extends PMCE {

  import PermessageDeflate._

  private var _encoder: Deflater = _
  private var _decoder: Inflater = _

  def name = "permessage-deflate"

  def extensionHeader = HttpHeaders.RawHeader("Sec-WebSocket-Extensions", (
    name
    + (if (server_no_context_takeover) "; " + SERVER_MAX_WINDOW_BITS else "")))

  private def getOrResetEncoder = {
    if (isServer) {
      if (_encoder == null) {
        _encoder = new Deflater(Deflater.BEST_COMPRESSION, true)
      }
      if (server_no_context_takeover) {
        _encoder.reset()
      }
    } else {
      if (_encoder == null) {
        _encoder = new Deflater(Deflater.BEST_COMPRESSION, true)
      }
      if (client_no_context_takeover) {
        _encoder.reset()
      }
    }
    _encoder
  }

  private def getOrResetDecoder = {
    if (isServer) {
      if (_decoder == null) {
        _decoder = new Inflater(true)
      }
      if (server_no_context_takeover) {
        _decoder.reset()
      }
    } else {
      if (_decoder == null) {
        _decoder = new Inflater(true)
      }
      if (client_no_context_takeover) {
        _decoder.reset()
      }
    }
    _decoder
  }

  def encode(input: ByteString): ByteString = {
    val encoder = getOrResetEncoder

    val accumulator = ByteString.newBuilder

    val uncompressed = input.toArray
    if (!encoder.finished) {
      encoder.setInput(uncompressed, 0, uncompressed.length)
      val compressed = Array.ofDim[Byte](uncompressed.length + OVERHEAD)
      while (!encoder.needsInput) {
        val len = encoder.deflate(compressed, 0, compressed.length, Deflater.SYNC_FLUSH) match {
          case len if len > 0 =>
            val dataLen = if (len > 4 && findSpecTail(compressed)) len - 4 else len
            accumulator.putBytes(compressed, 0, dataLen)
            if (BFINAL_HACK) {
              /**
               * Per the spec, it says that BFINAL 1 or 0 are allowed.
               * However, Java always uses BFINAL 1, whereas the browsers Chromium and Safari fail to decompress when it encounters BFINAL 1.
               * This hack will always set BFINAL 0
               */
              val b0 = accumulator.result()(0)
              if ((b0 & 1) != 0) { // if BFINAL 1
                //outbuf.put(0, (b0 ^= 1)); // TODO flip bit to BFINAL 0
              }
            }
          case _ =>
        }
      }
    }

    accumulator.result.compact
  }

  def decode(input: ByteString, isFin: Boolean): ByteString = {
    val decoder = getOrResetDecoder

    val inlen = input.length
    val compressed = if (isFin) {
      val xs = Array.ofDim[Byte](inlen + TAIL.length)
      input.copyToArray(xs, 0, inlen)
      System.arraycopy(TAIL, 0, xs, inlen, TAIL.length)
      xs
    } else {
      val xs = Array.ofDim[Byte](inlen)
      input.copyToArray(xs, 0, inlen)
      xs
    }

    decoder.setInput(compressed, 0, compressed.length)

    val accumulator = ByteString.newBuilder

    val outbuf = Array.ofDim[Byte](inlen)
    while (decoder.getRemaining > 0 && !decoder.finished) {
      try {
        decoder.inflate(outbuf) match {
          case 0 =>
            if (decoder.needsInput) {
              throw new Exception("Unable to inflate frame, not enough input on frame")
            }
            if (decoder.needsDictionary) {
              throw new Exception("Unable to inflate frame, frame erroneously says it needs a dictionary")
            }
          case len if len > 0 => accumulator.putBytes(outbuf, 0, len)
        }
      } catch {
        case ex: DataFormatException => throw ex
      }
    }

    accumulator.result.compact
  }

  def findSpecTail(data: Array[Byte]) = {
    val len = data.length
    var idx = len - 4
    var found = true
    var i = 0
    while (i < TAIL.length && !found) {
      if (data(idx + i) != TAIL(i)) {
        found = false
      }
      i += 1
    }
    found
  }

}
