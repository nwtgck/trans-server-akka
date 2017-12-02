package io.github.nwtgck.trans_server

import javax.crypto.Cipher

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl.Flow
import akka.stream.stage._
import akka.util.ByteString

object CipherFlow{
  /**
    * Flow for cipher
    * @param cipher
    * @return
    */
  def flow(cipher: Cipher): Flow[ByteString, ByteString, NotUsed] =
    Flow.fromGraph(new CipherStage(cipher))


  /**
    * CipherStage
    * (from: https://gist.github.com/TimothyKlim/ec5889aa23400529fd5e)
    * @param cipher
    */
  private class CipherStage(cipher: Cipher) extends GraphStage[FlowShape[ByteString, ByteString]] {
    val in: Inlet[ByteString]   = Inlet[ByteString]("in")
    val out: Outlet[ByteString] = Outlet[ByteString]("out")

    override val shape: FlowShape[ByteString, ByteString] = FlowShape.of(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            val bs = grab(in)
            if (bs.isEmpty) push(out, bs)
            else push(out, ByteString(cipher.update(bs.toArray)))
          }

          override def onUpstreamFinish(): Unit = {
            val bs = ByteString(cipher.doFinal())
            if (bs.nonEmpty) emit(out, bs)
            complete(out)
          }
        })

        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            pull(in)
          }
        })
      }
  }
}




