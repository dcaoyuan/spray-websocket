package spray.can.websocket.compress

import akka.util.ByteString
import spray.http.HttpHeader

/**
 * Per-message Compression Extension
 *
 * To support:
 *   permessage-deflate - done
 *   permessage-bzip2
 *   permessage-snappy
 *
 */
trait PMCE {
  def name: String
  def extensionHeader: HttpHeader
  def encode(input: ByteString): ByteString
  def decode(input: ByteString, isFin: Boolean): ByteString
}
