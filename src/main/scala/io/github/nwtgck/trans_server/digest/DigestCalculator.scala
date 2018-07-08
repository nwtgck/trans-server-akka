package io.github.nwtgck.trans_server.digest

import java.security.MessageDigest

import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.util.ByteString


// (from: https://doc.akka.io/docs/akka/2.5.13/stream/stream-cookbook.html?language=scala)
class DigestCalculator[Alg <: Algorithm](algorithm: Alg) extends GraphStage[FlowShape[ByteString, Digest[Alg]]] {
  val in: Inlet[ByteString]    = Inlet("DigestCalculator.in")
  val out: Outlet[Digest[Alg]] = Outlet("DigestCalculator.out")
  override val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private val digest = MessageDigest.getInstance(algorithm.name)

    setHandler(out, new OutHandler {
      override def onPull(): Unit = pull(in)
    })

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        val chunk = grab(in)
        digest.update(chunk.toArray)
        pull(in)
      }

      override def onUpstreamFinish(): Unit = {
        emit(out, Digest[Alg](digest.digest().map("%02x".format(_)).mkString))
        completeStage()
      }
    })
  }
}
