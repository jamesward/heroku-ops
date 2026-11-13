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
  /** The larger of each field: a minute's peak, across samples and dynos. */
  def max(o: MemorySample): MemorySample =
    MemorySample(total.max(o.total), rss.max(o.rss), cache.max(o.cache), swap.max(o.swap), quota.max(o.quota))

/** The latest memory sample of an app, and the dyno (`web.1`) it came from. */
final case class MemoryReading(at: Instant, dyno: String, sample: MemorySample)

enum Event derives CanEqual:
  case Request
  case Observed(signal: Signal, sample: String)
  case Measured(dyno: String, memory: MemorySample)

final case class Watched(app: AppName, repo: String, url: String, probe: String, routine: Option[RoutineId])

final case class Release(version: Int, description: String, at: Instant)
