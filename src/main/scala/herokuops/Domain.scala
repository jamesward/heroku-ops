package herokuops

import java.time.Instant

opaque type AppName = String

object AppName:
  private val Valid = "[a-z][a-z0-9-]{2,29}".r

  def parse(s: String): Either[String, AppName] =
    Either.cond(Valid.matches(s), s, s"not a Heroku app name: $s")

  extension (a: AppName) def value: String = a

  given CanEqual[AppName, AppName] = CanEqual.derived

opaque type RoutineId = String

object RoutineId:
  private val Valid = "trig_[A-Za-z0-9]+".r

  def parse(s: String): Either[String, RoutineId] =
    Either.cond(Valid.matches(s), s, s"not a routine id: $s")

  extension (r: RoutineId) def value: String = r

/** Something a log line, webhook or probe says about an app. */
enum Signal derives CanEqual:
  case Crash, Timeout, RouterError, ServerError, Memory, AppError, ProbeDown, ReleaseFailed, BuildFailed

/** A `log-runtime-metrics` memory sample (`sample#memory_*`, every 20 s per dyno), in MB. */
final case class MemorySample(total: Double, rss: Double, cache: Double, swap: Double, quota: Double) derives CanEqual:
  /** What Heroku's R14 compares with the quota (#25): `total` also counts page cache, which can be reclaimed. */
  def rssSwap: Double = rss + swap

/** A minute's peak, across samples and dynos: the larger of each field, and the largest `rss + swap` of any one sample (the peaks of rss and
  * swap can come from different samples, so their sum would overstate it). */
final case class MemoryPeak(fields: MemorySample, rssSwap: Double) derives CanEqual:
  def max(s: MemorySample): MemoryPeak =
    val f = fields
    MemoryPeak(MemorySample(f.total.max(s.total), f.rss.max(s.rss), f.cache.max(s.cache), f.swap.max(s.swap), f.quota.max(s.quota)), rssSwap.max(s.rssSwap))

object MemoryPeak:
  def of(s: MemorySample): MemoryPeak = MemoryPeak(s, s.rssSwap)

/** The latest memory sample of an app, and the dyno (`web.1`) it came from. */
final case class MemoryReading(at: Instant, dyno: String, sample: MemorySample)

enum Event derives CanEqual:
  case Request
  /** `request` is the router line's dyno and request id, for a timeout or 5xx (#24). */
  case Observed(signal: Signal, sample: String, request: Option[RequestRef] = None)
  case Measured(dyno: String, memory: MemorySample)

final case class Watched(app: AppName, repo: String, url: String, probe: String, routine: Option[RoutineId])

final case class Release(version: Int, description: String, at: Instant)
