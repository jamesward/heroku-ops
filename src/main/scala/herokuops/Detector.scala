package herokuops

import java.time.{Duration, Instant}

/** Rolling per-app counters, in per-minute buckets, and the rules that turn them into an alert. Pure: the caller owns the state. */
final case class Bucket(requests: Int, counts: Map[Signal, Int], samples: Map[Signal, List[String]])

final case class AppState(
    buckets: Map[Long, Bucket],
    probeFailures: Int,
    probeSuccesses: Int,
    down: Boolean,
    lastFire: Option[Instant],
)

final case class Reason(signal: Signal, count: Int)

final case class Alert(app: AppName, at: Instant, reasons: List[Reason], samples: List[String])

object Detector:
  val WindowMinutes = 5
  val KeepMinutes = 60
  val Cooldown: Duration = Duration.ofMinutes(30)
  val DownAfterFailures = 3
  val UpAfterSuccesses = 2

  /** How many of a signal in the window raise an alert. */
  private val Threshold: Map[Signal, Int] = Map(
    Signal.Crash -> 1, Signal.ReleaseFailed -> 1, Signal.BuildFailed -> 1,
    Signal.Memory -> 3, Signal.Timeout -> 5, Signal.RouterError -> 5, Signal.AppError -> 10,
  )
  private val ServerErrorMinimum = 10
  private val ServerErrorRatio = 0.05

  val empty: AppState = AppState(Map.empty, 0, 0, false, None)

  private def minute(at: Instant): Long = at.getEpochSecond / 60

  private val noBucket = Bucket(0, Map.empty, Map.empty)

  def record(s: AppState, event: Event, at: Instant): AppState =
    val m = minute(at)
    val b = s.buckets.getOrElse(m, noBucket)
    val updated = event match
      case Event.Request => b.copy(requests = b.requests + 1)
      case Event.Observed(sig, sample) =>
        b.copy(
          counts = b.counts.updated(sig, b.counts.getOrElse(sig, 0) + 1),
          samples = b.samples.updated(sig, (sample :: b.samples.getOrElse(sig, Nil)).take(3)),
        )
    s.copy(buckets = (s.buckets.updated(m, updated)).filter((k, _) => k > m - KeepMinutes))

  def probe(s: AppState, ok: Boolean): AppState =
    if ok then
      val successes = s.probeSuccesses + 1
      s.copy(probeFailures = 0, probeSuccesses = successes, down = s.down && successes < UpAfterSuccesses)
    else
      val failures = s.probeFailures + 1
      s.copy(probeFailures = failures, probeSuccesses = 0, down = s.down || failures >= DownAfterFailures)

  private def window(s: AppState, at: Instant): List[Bucket] =
    val m = minute(at)
    s.buckets.collect { case (k, b) if k > m - WindowMinutes && k <= m => b }.toList

  private def reasons(s: AppState, at: Instant): List[Reason] =
    val w = window(s, at)
    def total(sig: Signal) = w.map(_.counts.getOrElse(sig, 0)).sum
    val counted = Threshold.toList.collect { case (sig, min) if total(sig) >= min => Reason(sig, total(sig)) }
    val requests = w.map(_.requests).sum
    val errors = total(Signal.ServerError)
    val server = Option.when(errors >= ServerErrorMinimum && errors.toDouble / requests.max(1) >= ServerErrorRatio)(Reason(Signal.ServerError, errors))
    val probe = Option.when(s.down)(Reason(Signal.ProbeDown, s.probeFailures))
    counted ++ server ++ probe

  /** An alert when a rule fires and the app isn't in its cooldown, which also caps an app at two fires an hour. */
  def evaluate(app: AppName, s: AppState, at: Instant): (AppState, Option[Alert]) =
    val cooling = s.lastFire.exists(_.isAfter(at.minus(Cooldown)))
    val found = reasons(s, at)
    if found.isEmpty || cooling then (s, None)
    else
      val samples = window(s, at).flatMap(_.samples.values.flatten).distinct.take(5)
      (s.copy(lastFire = Some(at)), Some(Alert(app, at, found, samples)))
