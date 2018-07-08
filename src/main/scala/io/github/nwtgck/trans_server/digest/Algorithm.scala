package io.github.nwtgck.trans_server.digest


sealed abstract class Algorithm(val name: String)

// NOTE: algorithm list is found in https://stackoverflow.com/a/24983009/2885946
object Algorithm {
  case object MD2 extends Algorithm("MD2")
  case object MD5 extends Algorithm("MD5")
  case object `SHA` extends Algorithm("SHA")
  case object `SHA-1` extends Algorithm("SHA-1")
  case object `SHA-224` extends Algorithm("SHA-224")
  case object `SHA-256` extends Algorithm("SHA-256")
  case object `SHA-384` extends Algorithm("SHA-384")
  case object `SHA-512` extends Algorithm("SHA-512")
}
