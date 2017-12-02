package io.github.nwtgck.trans_server

object Util {
  // Generate hashed key
  // (from: http://www.casleyconsulting.co.jp/blog-engineer/java/%E3%80%90java-se-8%E9%99%90%E5%AE%9A%E3%80%91%E5%AE%89%E5%85%A8%E3%81%AA%E3%83%91%E3%82%B9%E3%83%AF%E3%83%BC%E3%83%89%E3%82%92%E7%94%9F%E6%88%90%E3%81%99%E3%82%8B%E6%96%B9%E6%B3%95/)
  def generateHashedKey1(key: String, salt: String): String = {
    import javax.crypto.SecretKey
    import javax.crypto.SecretKeyFactory
    import javax.crypto.spec.PBEKeySpec
    import java.security.MessageDigest

    val messageDigest = MessageDigest.getInstance("SHA-256")
    val passCharAry: Array[Char] = key.toCharArray
    messageDigest.update(salt.getBytes)
    val hashedSalt: Array[Byte] = messageDigest.digest

    val keySpec: PBEKeySpec = new PBEKeySpec(passCharAry, hashedSalt, 10000, 256)

    val skf: SecretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")


    var secretKey: SecretKey = skf.generateSecret(keySpec)

    val passByteAry: Array[Byte] = secretKey.getEncoded

    // Convert byte array to a string
    val sb: StringBuilder = new StringBuilder(64)
    for (b <- passByteAry) {
      sb.append("%02x".format(b & 0xff))
    }
    sb.toString()
  }
}
