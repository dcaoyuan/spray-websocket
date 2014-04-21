/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spray.io

import java.nio.ByteBuffer
import javax.net.ssl.{ SSLException, SSLEngineResult }
import scala.annotation.tailrec
import akka.util.{ ByteStringBuilder, ByteString }
import akka.io.Tcp
import spray.http.HttpData
import spray.util._
import SSLEngineResult.Status._
import SSLEngineResult.HandshakeStatus._

/**
 * A pipeline stage that provides SSL support.
 *
 * One thing to keep in mind is that there's no support for half-closed connections
 * in SSL (but SSL on the other side requires half-closed connections from its transport
 * layer). This means:
 * 1. keepOpenOnPeerClosed is not supported on top of SSL (once you receive PeerClosed the connection is closed, further
 *    CloseCommands are ignored)
 * 2. keepOpenOnPeerClosed should always be enabled on the transport layer beneath SSL so that one can wait for
 *    the other side's SSL level close_notify message without barfing RST to the peer because this socket is already gone
 */
object SslTlsSupportV2 {

  def apply(maxEncryptionChunkSize: Int, publishSslSessionInfo: Boolean,
            tracing: Boolean = false): OptionalPipelineStage[SslTlsContext] =
    new OptionalPipelineStage[SslTlsContext] {
      def enabled(context: SslTlsContext) = context.sslEngine.isDefined

      def applyIfEnabled(context: SslTlsContext, commandPL: CPL, eventPL: EPL): Pipelines =
        new DynamicPipelines {
          import context._
          val engine = context.sslEngine.get
          var pendingInboundBytes: ByteBuffer = EmptyByteBuffer // encrypted bytes to be decrypted
          var pendingOutboundBytes: ByteBuffer = EmptyByteBuffer // plaintext bytes to be encrypted
          val pendingEncryptedBytes = new ByteStringBuilder // encrypted bytes to be sent

          var pendingSentAcks = 0

          become(defaultState())

          // No ACK pending,
          // if the given stream is empty no write is currently pending, otherwise its head is the currently pending write
          // if closedEvent is defined an outbound close is scheduled after the current chunk stream is sent
          def defaultState(remainingOutgoingData: Stream[WriteChunk] = Stream.empty,
                           writeWantsAck: Option[WriteChunk] = None,
                           closedEvent: Option[Tcp.ConnectionClosed] = None): State = new State {
            if (tracing) log.debug("Transitioning to defaultState")
            val commandPipeline: CPL = {
              case x: Tcp.WriteCommand ⇒
                if (tracing) log.debug("Received write {} in defaultState", x.getClass)
                startSending(x, remainingOutgoingData, closedEvent, sendNow = true)
              case x @ (Tcp.Close | Tcp.ConfirmedClose) ⇒
                log.debug("Closing outbound SSL stream due to reception of {}", x)
                startClosing(x.asInstanceOf[Tcp.CloseCommand].event)
              case Tcp.Abort ⇒ abort() // do we need to close anything in this case?
              case cmd       ⇒ commandPL(cmd)
            }
            val eventPipeline: EPL = {
              case Tcp.Received(data) ⇒
                if (tracing) log.debug("Received {} inbound bytes in defaultState", data.size)
                enqueueInboundBytes(data)
                decrypt()
                if (encryptedBytesPending || isOutboundDone) {
                  sendEncryptedBytes() // might send an empty Tcp.Write, but always triggers a pending ACK
                  become {
                    if (isOutboundDone) finishingClose(closedEvent)
                    else waitingForAck(remainingOutgoingData, writeWantsAck, closedEvent)
                  }
                }
              case Tcp.PeerClosed     ⇒ receivedUnexpectedPeerClosed()
              case x: Tcp.ErrorClosed ⇒ eventPL(x) // is there anything we need to close in this case?
              case x @ (_: Tcp.ConnectionClosed | WriteChunkAck) ⇒
                throw new IllegalStateException("Received " + x + " in defaultState")
              case ev ⇒ eventPL(ev)
            }
          }

          // ACK pending,
          // if the given stream is empty no write is currently pending, otherwise its head is the currently pending write
          // if closedEvent is defined an outbound close is scheduled after the current chunk stream is sent
          def waitingForAck(remainingOutgoingData: Stream[WriteChunk] = Stream.empty,
                            writeWantsAck: Option[WriteChunk] = None,
                            closedEvent: Option[Tcp.ConnectionClosed] = None): State = new State {
            if (tracing) log.debug("Transitioning to waitingForAck")
            val commandPipeline: CPL = {
              case x: Tcp.WriteCommand ⇒
                if (tracing) log.debug("Received write {} in waitingForAck", x.getClass)
                startSending(x, remainingOutgoingData, closedEvent, sendNow = false)
              case x @ (Tcp.Close | Tcp.ConfirmedClose) ⇒
                if (closedEvent.isEmpty) {
                  log.debug("Scheduling close of outbound SSL stream due to reception of {}", x)
                  become(waitingForAck(remainingOutgoingData, writeWantsAck, Some(x.asInstanceOf[Tcp.CloseCommand].event)))
                } else log.debug("Dropping {} since an SSL-level close is already scheduled", x)
              case Tcp.Abort ⇒ abort() // do we need to close anything in this case?
              case cmd       ⇒ commandPL(cmd)
            }
            val eventPipeline: EPL = {
              case Tcp.Received(data) ⇒
                if (tracing) log.debug("Received {} inbound bytes in waitingForAck", data.size)
                enqueueInboundBytes(data)
                decrypt()
                if (isOutboundDone) become(finishingClose(closedEvent)) // else stay in this state
              case WriteChunkAck ⇒
                if (tracing) log.debug("Received WriteChunkAck in waitingForAck")
                pendingSentAcks -= 1
                if (encryptedBytesPending) sendEncryptedBytes()
                else {
                  if (pendingOutboundBytes.hasRemaining) {
                    if (pendingSentAcks == 0) become(defaultState(remainingOutgoingData, writeWantsAck, closedEvent)) // we need to wait for incoming inbound data
                  } else if (remainingOutgoingData.isEmpty) {
                    writeWantsAck foreach { x => if (x.write.wantsAck) eventPL(x.write.ack) }
                    startClosingOrReturnToDefaultState()
                  } else {
                    if (tracing) log.debug("Finished sending write chunk")
                    writeWantsAck foreach { x => if (x.write.wantsAck) eventPL(x.write.ack) }
                    if (remainingOutgoingData.isEmpty) startClosingOrReturnToDefaultState()
                    else startEncrypting(remainingOutgoingData, sendNow = true, closedEvent)
                  }
                }
              case Tcp.PeerClosed          ⇒ receivedUnexpectedPeerClosed()
              case x: Tcp.ErrorClosed      ⇒ eventPL(x) // is there anything we need to close in this case?
              case x: Tcp.ConnectionClosed ⇒ throw new IllegalStateException("Received " + x + " in waitingForAck")
              case ev                      ⇒ eventPL(ev)
            }
            def startClosingOrReturnToDefaultState(): Unit =
              closedEvent match {
                case Some(ev) ⇒ startClosing(ev)
                case None     ⇒ if (pendingSentAcks == 0) become(defaultState())
              }
          }

          // SSLEngine is outbound done, ACK pending for SSL-level closing sequence bytes that were already sent
          // the inbound side might still be open, however we don't wait for the peer's closing bytes
          // but simply issue a ConfirmedClose to the TCP layer
          def finishingClose(closedEvent: Option[Tcp.ConnectionClosed] = None,
                             closeCommand: Tcp.CloseCommand = Tcp.ConfirmedClose): State = new State {
            if (tracing) log.debug("Transitioning to finishClose({}, {})", closedEvent, closeCommand)
            commandPL(closeCommand)
            val commandPipeline: CPL = {
              case x: Tcp.WriteCommand                  ⇒ failWrite(x, "the SSL connection is already closing")
              case x @ (Tcp.Close | Tcp.ConfirmedClose) ⇒ log.debug("Dropping {} since the SSL connection is already closing", x)
              case Tcp.Abort                            ⇒ abort() // do we need to close anything in this case?
              case cmd                                  ⇒ commandPL(cmd)
            }
            val eventPipeline: EPL = {
              case Tcp.Received(data) ⇒
                if (tracing) log.debug("Received {} inbound bytes in finishingClose", data.size)
                if (!engine.isInboundDone) {
                  enqueueInboundBytes(data)
                  try decrypt()
                  catch {
                    // SSLEngine may fail with IllegalStateException in some cases when receiving unexpected kinds of
                    // SSL records after being closed, see https://github.com/spray/spray/issues/743
                    case ex: IllegalStateException ⇒ log.debug("Ignoring exception in finishingClose: {}", ex.getMessage)
                  }
                } else log.warning("Dropping {} bytes that were received after SSL-level close", data.size)
              case WriteChunkAck           ⇒ pendingSentAcks -= 1 // ignore, expected as ACK for the closing outbound bytes
              case Tcp.PeerClosed          ⇒ // expected after the final inbound packet, we simply drop it here
              case _: Tcp.ConnectionClosed ⇒ eventPL(closedEvent getOrElse Tcp.PeerClosed)
              // TODO: remove this work-around for https://github.com/akka/akka/pull/1800 (1801)
              case Pipeline.ActorDeath(_)  ⇒ eventPL(closedEvent getOrElse Tcp.PeerClosed)
              case ev                      ⇒ eventPL(ev)
            }
          }

          def startSending(write: Tcp.WriteCommand, remainingOutgoingData: Stream[WriteChunk],
                           closedEvent: Option[Tcp.ConnectionClosed], sendNow: Boolean): Unit =
            if (closedEvent.isEmpty) {
              val chunkStream = writeChunkStream(write)
              if (chunkStream.nonEmpty) {
                startEncrypting(remainingOutgoingData append chunkStream, sendNow)
              }
              // else ignore
            } else failWrite(write, "the SSL connection is already closing")

          def startEncrypting(chunkStream: Stream[WriteChunk],
                              sendNow: Boolean,
                              closedEvent: Option[Tcp.ConnectionClosed] = None): Unit =
            if (chunkStream.head.buffer.hasRemaining) {
              setPendingOutboundBytes(chunkStream.head.buffer)
              encrypt()
              if (sendNow) sendEncryptedBytes()
              become(waitingForAck(chunkStream.tail, Some(chunkStream.head), closedEvent))
            } else {
              // shortcut for empty writes possibly containing acks (created by user or `writeChunkStream` below)
              become(waitingForAck(chunkStream.tail, Some(chunkStream.head), closedEvent))
              // must come after the 'become', otherwise an infinite loop might be triggered
              if (sendNow) {
                pendingSentAcks += 1
                eventPipeline(WriteChunkAck)
              }
            }

          def startClosing(closedEvent: Tcp.ConnectionClosed): Unit = {
            engine.closeOutbound()
            encrypt()
            sendEncryptedBytes()
            become {
              if (isOutboundDone) finishingClose(Some(closedEvent))
              else waitingForAck(closedEvent = Some(closedEvent))
            }
          }

          def receivedUnexpectedPeerClosed(): Unit = {
            log.debug("Received unexpected Tcp.PeerClosed, invalidating SSL session")
            try engine.closeInbound() // invalidates SSL session and should throw SSLException
            catch { case e: SSLException ⇒ } // ignore warning about truncation attack
            become(finishingClose(Some(Tcp.ErrorClosed("Peer closed SSL connection prematurely")), Tcp.Close))
          }

          def abort(): Unit = {
            commandPL(Tcp.Abort)
            become {
              new State {
                def commandPipeline = commandPL
                val eventPipeline: EPL = {
                  case Tcp.Received(data) ⇒ log.debug("Dropping {} received bytes due to connection having been aborted", data.size)
                  case ev                 ⇒ eventPL(ev)
                }
              }
            }
          }

          override def process[T](msg: T, pl: Pipeline[T]): Unit =
            try pl(msg)
            catch {
              case e: SSLException ⇒
                log.error("Aborting encrypted connection to {} due to {}", context.remoteAddress, Utils.fullErrorMessageFor(e))
                abort()
            }

          abstract class PumpAction {
            // returns true if the action was completed successfully,
            // otherwise the connection has already been aborted
            def apply(): Unit = {
              val tempBuf = SslBufferPool.acquire()
              try apply(tempBuf)
              finally SslBufferPool.release(tempBuf)
            }
            def apply(tempBuf: ByteBuffer): Unit
          }

          // pumps inbound and outbound data through the SSLEngine starting with encrypting
          // potentially decrypted bytes are immediately dispatched to the eventPL
          // potentially encrypted bytes are accumulated in the pendingEncryptedBytes builder
          // pumping is only stopped by a buffer underflow on the inbound side or when both sides are done
          val encrypt: PumpAction = new PumpAction {
            @tailrec def apply(tempBuf: ByteBuffer): Unit = {
              tempBuf.clear()
              val result = engine.wrap(pendingOutboundBytes, tempBuf)
              tempBuf.flip()
              if (tempBuf.hasRemaining) pendingEncryptedBytes ++= ByteString(tempBuf)
              result.getStatus match {
                case OK ⇒ result.getHandshakeStatus match {
                  case status @ (NOT_HANDSHAKING | FINISHED) ⇒
                    if (status == FINISHED) publishSSLSessionEstablished()
                    if (pendingOutboundBytes.hasRemaining) apply(tempBuf)
                    else decrypt(tempBuf)
                  case NEED_WRAP   ⇒ apply(tempBuf)
                  case NEED_UNWRAP ⇒ decrypt(tempBuf)
                  case NEED_TASK   ⇒ runDelegatedTasks(); apply(tempBuf)
                }
                case CLOSED           ⇒ if (!engine.isInboundDone) decrypt(tempBuf)
                case BUFFER_OVERFLOW  ⇒ throw new IllegalStateException // the SslBufferPool should make sure that buffers are never too small
                case BUFFER_UNDERFLOW ⇒ throw new IllegalStateException // should never appear as a result of a wrap
              }
            }
          }

          // same as `encrypt` but starts with decrypting
          val decrypt: PumpAction = new PumpAction {
            @tailrec def apply(tempBuf: ByteBuffer): Unit = {
              tempBuf.clear()
              val result = engine.unwrap(pendingInboundBytes, tempBuf)
              tempBuf.flip()
              if (tempBuf.hasRemaining) {
                if (tracing) log.debug("Dispatching {} decrypted bytes: {}", tempBuf.remaining, tempBuf.duplicate.drainToString)
                eventPL(Tcp.Received(ByteString(tempBuf)))
              }
              result.getStatus match {
                case OK ⇒ result.getHandshakeStatus match {
                  case status @ (NOT_HANDSHAKING | FINISHED) ⇒
                    if (status == FINISHED) publishSSLSessionEstablished()
                    if (pendingInboundBytes.hasRemaining) apply(tempBuf)
                    else encrypt(tempBuf)
                  case NEED_UNWRAP ⇒ apply(tempBuf)
                  case NEED_WRAP   ⇒ encrypt(tempBuf)
                  case NEED_TASK   ⇒ runDelegatedTasks(); apply(tempBuf)
                }
                case CLOSED ⇒
                  if (!engine.isOutboundDone) {
                    engine.closeOutbound() // when the inbound side is done we immediately close the outbound side as well
                    encrypt(tempBuf) // and continue pumping (until both sides are done or we have a BUFFER_UNDERFLOW)
                  }
                case BUFFER_UNDERFLOW ⇒ // too few inbound data, stop "pumping"
                case BUFFER_OVERFLOW  ⇒ throw new IllegalStateException // the SslBufferPool should make sure that buffers are never too small
              }
            }
          }

          def sendEncryptedBytes(): Unit = {
            val result = pendingEncryptedBytes.result()
            pendingEncryptedBytes.clear()
            if (tracing) log.debug("Sending {} encrypted bytes", result.size)
            pendingSentAcks += 1
            commandPL(Tcp.Write(result, WriteChunkAck))
          }

          def encryptedBytesPending = pendingEncryptedBytes.length > 0 // why doesn't ByteStringBuilder have an `isEmpty`?

          def setPendingOutboundBytes(buffer: ByteBuffer): Unit = {
            verify(!pendingOutboundBytes.hasRemaining)
            pendingOutboundBytes = buffer
          }

          def enqueueInboundBytes(data: ByteString): Unit =
            pendingInboundBytes =
              if (pendingInboundBytes.hasRemaining) {
                val buffer = ByteBuffer.allocate(pendingInboundBytes.remaining + data.size)
                buffer.put(pendingInboundBytes)
                data.copyToBuffer(buffer)
                buffer.flip()
                buffer
              } else data.toByteBuffer

          @tailrec def runDelegatedTasks(): Unit = {
            val task = engine.getDelegatedTask
            if (task != null) {
              task.run()
              runDelegatedTasks()
            }
          }

          def isOutboundDone: Boolean =
            if (engine.isInboundDone) {
              // our pumping logic should make sure that we immediately outbound close
              // after having detected an inbound close
              verify(engine.isOutboundDone)
              true
            } else engine.isOutboundDone

          def publishSSLSessionEstablished(): Unit = {
            if (tracing) log.debug("Publishing SSLSessionInfo")
            if (publishSslSessionInfo) eventPL(SSLSessionEstablished(SSLSessionInfo(engine)))
          }

          def failWrite(cmd: Tcp.WriteCommand, msg: String): Unit = {
            log.debug("Failing write command because " + msg)
            context.self ! cmd.failureMessage
          }

          def verify(condition: Boolean): Unit =
            if (!condition) throw new IllegalStateException
        }

      def writeChunkStream(cmd: Tcp.WriteCommand): Stream[WriteChunk] = {
        def chunkStream(httpData: HttpData, write: Tcp.SimpleWriteCommand): Stream[WriteChunk] =
          if (httpData.isEmpty) Stream.cons(WriteChunk(EmptyByteBuffer, write), Stream.empty)
          else {
            val result = httpData.toChunkStream(maxEncryptionChunkSize) map { data ⇒
              WriteChunk(ByteBuffer.wrap(data.toByteArray), Tcp.Write.empty)
            }

            if (write.wantsAck) result append Stream(WriteChunk(EmptyByteBuffer, write))
            else result
          }
        cmd match {
          case w @ Tcp.Write.empty                     ⇒ Stream.empty
          case w @ Tcp.Write(bytes, _)                 ⇒ chunkStream(HttpData(bytes), w)
          case w @ Tcp.WriteFile(path, offset, len, _) ⇒ chunkStream(HttpData.fromFile(path, offset, len), w)
          case Tcp.CompoundWrite(head, tail)           ⇒ writeChunkStream(head).append(writeChunkStream(tail))
        }
      }
    }

  private case class WriteChunk(buffer: ByteBuffer, write: Tcp.SimpleWriteCommand)
  private object WriteChunkAck extends Event

  private val EmptyByteBuffer = ByteBuffer.wrap(spray.util.EmptyByteArray)

  /** Event dispatched upon successful SSL handshaking. */
  case class SSLSessionEstablished(info: SSLSessionInfo) extends Event
}