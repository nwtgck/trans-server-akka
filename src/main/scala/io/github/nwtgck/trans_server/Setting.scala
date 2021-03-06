package io.github.nwtgck.trans_server

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
  * Created by Ryo on 2017/04/23.
  */
object Setting {
  val HOST                 : String          = "0.0.0.0"
  val DEFAULT_HTTP_PORT    : Int             = 8181
  val DEFAULT_HTTPS_PORT   : Int             = 4343
  val File_DB_PATH         : String          = "db/file_db"
  val KEY_STORE_PATH       : String          = "trans.keystore"
  val KEY_STORE_PASSWORD   : String          = "changeit"
  val DefaultStoreDuration : FiniteDuration  = 1.hour
  val MaxStoreDuration     : FiniteDuration  = 30.days
  val CleanupDuration      : FiniteDuration  = 1.minute
  val DefaultIdLength      : Int             = 3
  val MinIdLength          : Int             = 3
  val MaxIdLength          : Int             = 128
  val minSpecifiedFileIdLength : Int         = 5
  val MaxRedirectionUriLength: Int           = 10000000
  val KeySalt              : String          = "ymiKicjOq5M3yIMmVBEPFcLnkNxJ4n2iY9ms3fYRsBDS3wZvS1lev9ToLnZhlj3O"
  val FileEncryptionKey    : String          = "vKOhINCMuO47xxel" // NOTE: Its length should be 16
  val FileIdGenTryLimit    : Int             = 500 // NOTE: Simple study shows 500 is enough (https://github.com/nwtgck/random-str-collision-graph-scala)
  // 1 ~ 9 + 'a' ~ 'z' except misunderstandable chars for file ID
  val candidateChars       : IndexedSeq[Char] = (('0' to '9') ++ ('a' to 'z')).diff(Seq('g', '9', 'q', 'l', '1', 'o', '0'))
  // More secure candidate characters
  val secureCandidateChars : IndexedSeq[Char] = ('0' to '9') ++ ('a' to 'z') ++ ('A' to 'Z') ++ Seq('-', '_')
  val Md5HttpHeaderName    : String           = "X-FILE-MD5"
  val Sha1HttpHeaderName   : String           = "X-FILE-SHA1"
  val Sha256HttpHeaderName : String           = "X-FILE-SHA256"
  // Path name to specify File ID
  val fileIdFixationPathName : String         = "fix"

  // Reserved route name of GET request
  object GetRouteName{
    val Help     : String = "help"
    val Version  : String = "version"
    val Send     : String = "send"
    val Redirect : String = "r"

    // Reserved route names
    val allSet   : Set[String] = Set(
      Help,
      Version,
      Send,
      Redirect
    )
  }
}
