package io.github.nwtgck.trans_server.digest

/**
  * Message Digest Typed by Algorithm
  * @param value
  * @tparam Alg
  */
case class Digest[Alg <: Algorithm](value: String) extends AnyVal
