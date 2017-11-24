import scala.concurrent.duration.FiniteDuration

object Datetime{
  def now: Datetime = new Datetime(System.currentTimeMillis())
}

class Datetime(val millis: Long) extends AnyVal with Ordered[Datetime]{
  def toUtilDate = new java.util.Date(millis)
  override def toString: String = toUtilDate.toString

  override def compare(that: Datetime): Int = this.millis.compareTo(that.millis)

  def +(duration: FiniteDuration): Datetime = {
    new Datetime(millis + duration.length)
  }
}