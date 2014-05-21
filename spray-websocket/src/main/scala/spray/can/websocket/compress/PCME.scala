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

object PMCE {
  type BUILDER = Map[String, String] => PMCE

  private var _registedPMCEs = Map[String, BUILDER]()
  def registedPMCEs = _registedPMCEs

  def register(name: String, builder: BUILDER) {
    _registedPMCEs += (name -> builder)
  }

  def apply(name: String, params: Map[String, String]): Option[PMCE] = {
    _registedPMCEs.get(name) map { builder => builder(params) }
  }

  register(PermessageDeflate.name, PermessageDeflate.apply)
}