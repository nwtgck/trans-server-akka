package io.github.nwtgck.trans_server

import akka.http.scaladsl.HttpsConnectionContext
import org.scalatest.FunSpec

class UtilSpec extends FunSpec {

  describe("generateHttpsConnectionContext") {
    it("should generate HTTPS context without exception") {
      // Get keystore path
      val keyStorePath: String = getClass.getClassLoader.getResource("trans.keystore").getPath
      // Just create http context
      // (NOTE: Check whether HTTP context can be created without any error)
      val context: HttpsConnectionContext = Util.generateHttpsConnectionContext(
        keystorePassword = "changeit",
        keyStorePath     = keyStorePath
      )
    }
  }

}
