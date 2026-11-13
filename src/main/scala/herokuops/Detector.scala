package herokuops

import java.time.{Duration, Instant}

/** Rolling per-app counters, in per-minute buckets, and the rules that turn them into an alert. Pure: the caller owns the state. */
final case class Bucket(requests: Int, counts: Map[Signal, Int], samples: Map[Signal, List[String]], memory: Option[MemorySample] = None)

final case class AppState(
    buckets: Map[Long, Bucket],
    probeFailures: Int,
    probeSuccesses: Int,
    down: Boolean,
    active: Set[Signal],
    lastFire: Map[Signal, Instant],
    /** When the last crash line was counted, so the lines of one crash count once (see [[Detector.CrashLines]]). */
    lastCrash: Option[Instant] = None,
    /** Webhook `dyno crashed` reports not yet counted, held for [[Detector.ReleaseRestart]] in case a release explains them. */
    heldDynoCrashes: List[Instant] = Nil,
    lastRelease: Option[Instant] = None,
    lastMemory: Option[MemoryReading] = None,
)

final case class Reason(signal: Signal, count: Int) derives CanEqual

final case class Alert(app: AppName, at: Instant, reasons: List[Reason], samples: List[String])

object Detector:
  val WindowMinutes = 5
  val KeepMinutes = 60
  /** A condition that clears and comes back alerts again only after this long, so a flapping one doesn't fire repeatedly. */
  val Cooldown: Duration = Duration.ofMinutes(30)
  /** A condition that never clears (javadocs has been over its memory quota for hours) alerts again only after this long. */
  val Repeat: Duration = Duration.ofHours(24)
  /** One crash writes several lines within a second (R15, `State changed from up to crashed`, the webhook's `dyno crashed`): lines this
    * close together count as one crash, and all of them are kept as samples. */
  val CrashLines: Duration = Duration.ofSeconds(10)
  /** A release restarts the dynos, and the dyno webhook reports that as `crashed` 1–2 s before or after the release webhook (javadocs v244
    * and v247). A webhook-only `dyno crashed` this close to a release isn't a crash. */
  val ReleaseRestart: Duration = Duration.ofSeconds(30)
  val DownAfterFailures = 3
  val UpAfterSuccesses = 2

  /** How many of a signal in the window raise an alert. */
  private val Thresholds: Map[Signal, Int] = Map(
    Signal.Crash -> 1, Signal.ReleaseFailed -> 1, Signal.BuildFailed -> 1,
    Signal.Memory -> 3, Signal.Timeout -> 5, Signal.RouterError -> 5, Signal.AppError -> 10,
  )
  /** 5xx are rare in practice (none in hours of webjars, file-service, javadocs and semver traffic), so a few are news. */
  val ServerErrorMinimum = 5
  val ServerErrorRatio = 0.01

  def threshold(signal: Signal): Int = Thresholds(signal)

  val empty: AppState = AppState(Map.empty, 0, 0, false, Set.empty, Map.empty)

  private def minute(at: Instant): Long = at.getEpochSecond / 60

  private val noBucket = Bucket(0, Map.empty, Map.empty)

  def record(s: AppState, event: Event, at: Instant): AppState =
    val m = minute(at)
    val b = s.buckets.getOrElse(m, noBucket)
    val updated = event match
      case Event.Request => b.copy(requests = b.requests + 1)
      case Event.Measured(_, memory) => b.copy(memory = Some(b.memory.fold(memory)(_.max(memory))))
      case Event.Observed(sig, sample) =>
        val sameCrash = sig == Signal.Crash && s.lastCrash.exists(near(_, at, CrashLines))
        b.copy(
          counts = if sameCrash then b.counts else b.counts.updated(sig, b.counts.getOrElse(sig, 0) + 1),
          samples = b.samples.updated(sig, (sample :: b.samples.getOrElse(sig, Nil)).take(3)),
        )
    val crashed = event match
      case Event.Observed(Signal.Crash, _) => Some(s.lastCrash.filter(_.isAfter(at)).getOrElse(at))
      case _                               => s.lastCrash
    val measured = event match
      case Event.Measured(dyno, memory) if s.lastMemory.forall(!_.at.isAfter(at)) => Some(MemoryReading(at, dyno, memory))
      case _                                                                       => s.lastMemory
    s.copy(buckets = (s.buckets.updated(m, updated)).filter((k, _) => k > m - KeepMinutes), lastCrash = crashed, lastMemory = measured)

  private def near(a: Instant, b: Instant, within: Duration): Boolean =
    !a.isBefore(b.minus(within)) && !a.isAfter(b.plus(within))

  /** A dyno webhook said `crashed`. Dropped when a release came within [[ReleaseRestart]] before it; otherwise held until [[settle]]. */
  def dynoCrashed(s: AppState, at: Instant): AppState =
    if s.lastRelease.exists(near(_, at, ReleaseRestart)) then s
    else s.copy(heldDynoCrashes = at :: s.heldDynoCrashes)

  /** A release succeeded: held dyno crashes within [[ReleaseRestart]] of it were its restart, not crashes. */
  def released(s: AppState, at: Instant): AppState =
    s.copy(lastRelease = Some(at), heldDynoCrashes = s.heldDynoCrashes.filterNot(near(_, at, ReleaseRestart)))

  val DynoCrashedSample = "dyno crashed (webhook)"

  /** Counts the held dyno crashes that no release explained within [[ReleaseRestart]], at the time they were reported. Returns their times,
    * for the recent events. */
  def settle(s: AppState, at: Instant): (AppState, List[Instant]) =
    val (due, held) = s.heldDynoCrashes.partition(t => !t.isAfter(at.minus(ReleaseRestart)))
    val counted = due.sorted.foldLeft(s.copy(heldDynoCrashes = held))((st, t) => record(st, Event.Observed(Signal.Crash, DynoCrashedSample), t))
    (counted, due.sorted)

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

  /** What is wrong right now, whether or not it has alerted. */
  def conditions(s: AppState, at: Instant): List[Reason] = reasons(s, at)

  def minuteOf(at: Instant): Long = minute(at)

  private def reasons(s: AppState, at: Instant): List[Reason] =
    val w = window(s, at)
    def total(sig: Signal) = w.map(_.counts.getOrElse(sig, 0)).sum
    val counted = Thresholds.toList.collect { case (sig, min) if total(sig) >= min => Reason(sig, total(sig)) }
    val requests = w.map(_.requests).sum
    val errors = total(Signal.ServerError)
    val server = Option.when(errors >= ServerErrorMinimum && errors.toDouble / requests.max(1) >= ServerErrorRatio)(Reason(Signal.ServerError, errors))
    val probe = Option.when(s.down)(Reason(Signal.ProbeDown, s.probeFailures))
    counted ++ server ++ probe

  /** An alert for each condition that has just started (and hasn't alerted in the last [[Cooldown]]), or has lasted [[Repeat]]. */
  def evaluate(app: AppName, s: AppState, at: Instant): (AppState, Option[Alert]) =
    val found = reasons(s, at)
    val due = found.filter: r =>
      val last = s.lastFire.get(r.signal)
      val onset = !s.active.contains(r.signal)
      last.forall(l => (onset && !l.isAfter(at.minus(Cooldown))) || !l.isAfter(at.minus(Repeat)))
    val next = s.copy(active = found.map(_.signal).toSet, lastFire = s.lastFire ++ due.map(_.signal -> at))
    if due.isEmpty then (next, None)
    else
      val samples = window(s, at).flatMap(_.samples.values.flatten).distinct.take(5)
      (next, Some(Alert(app, at, due, samples)))
