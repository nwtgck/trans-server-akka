import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
  * Created by Ryo on 2017/04/23.
  */
object Setting {
  val HOST                 : String         = "0.0.0.0"
  val DEFAULT_HTTP_PORT    : Int            = 8181
  val DEFAULT_HTTPS_PORT   : Int            = 4343
  val File_DB_PATH         : String         = "db/file_db"
  val KEY_STORE_PATH       : String         = "trans.keystore"
  val KEY_STORE_PASSWORD   : String         = "changeit"
  val DefaultStoreDuration : FiniteDuration = 1.hour
  val MaxStoreDuration     : FiniteDuration = 30.days
  val CleanupDuration      : FiniteDuration = 1.minute
  val DefaultIdLength      : Int            = 3
  val MinIdLength          : Int            = 3
  val MaxIdLength          : Int            = 256
  val KeySalt              : String         = "ymiKicjOq5M3yIMmVBEPFcLnkNxJ4n2iY9ms3fYRsBDS3wZvS1lev9ToLnZhlj3O"
}
