package herokuops

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters.*

/** Replays a `heroku logs -a <app> -n 1500` dump through the classifier and detector and prints the alerts it would have
  * fired. A dev tool for tuning thresholds against real traffic:
  * `./sbta "Test / runMain herokuops.Replay /tmp/webjars.log webjars"` */
object Replay:
  private val Line = raw"^(\S+) (\w+)\[([^\]]+)\]: (.*)$$".r

  def main(args: Array[String]): Unit =
    val app = AppName.parse(args(1)).toOption.get
    val parsed = Files.readAllLines(Path.of(args(0))).asScala.toList.flatMap:
      case Line(ts, source, proc, text) => scala.util.Try(Instant.parse(ts.take(19) + "Z")).toOption.map((_, LogMessage(source, proc, text)))
      case _                            => None
    val signals = parsed.flatMap((_, m) => Classifier.classify(m)).collect { case Event.Observed(s, _) => s }.groupBy(identity).view.mapValues(_.size).toMap
    val requests = parsed.flatMap((_, m) => Classifier.classify(m)).count(_ == Event.Request)
    val (_, alerts) = parsed.foldLeft((Detector.empty, List.empty[Alert])):
      case ((state, found), (at, msg)) =>
        val recorded = Classifier.classify(msg).foldLeft(state)(Detector.record(_, _, at))
        val (next, alert) = Detector.evaluate(app, recorded, at)
        (next, found ++ alert)
    println(s"${app.value}: ${parsed.size} lines, $requests requests, signals $signals")
    if alerts.isEmpty then println("  no alerts")
    alerts.foreach(a => println(s"  ALERT ${a.at} ${a.reasons.map(r => s"${r.signal} x${r.count}").mkString(", ")}"))
