import scala.concurrent.duration.FiniteDuration
import java.sql.Timestamp

object TimestampUtil{


  object RichTimestampImplicit {
    implicit class RichTimestamp(self: Timestamp){
      def +(duration: FiniteDuration): Timestamp = {
        new Timestamp(self.getTime + duration.toMillis)
      }
    }
  }


  def now(): Timestamp = new Timestamp(System.currentTimeMillis())
}