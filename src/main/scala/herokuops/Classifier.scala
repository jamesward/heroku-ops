package herokuops

/** Heuristics that turn a log line into events. Detection is patterns and counts, not AI. */
object Classifier:
  private val Pair = raw"""(\w+)=("([^"]*)"|\S*)""".r
  private val Exit = raw"Process exited with status (\d+)".r.unanchored
  private val Crashed = raw"State changed from \w+ to crashed".r.unanchored
  private val Memory = raw"Error (R1[45]) ".r.unanchored
  private val JsonError = raw""""level"\s*:\s*"(ERROR|FATAL)"""".r.unanchored
  private val TextError = raw"\b(ERROR|FATAL)\b".r.unanchored
  private val Path = raw"""path="([^"?]*)\?[^"]*"""".r
  private val Control = raw"[\p{Cntrl}&&[^\t]]".r

  def logfmt(text: String): Map[String, String] =
    Pair.findAllMatchIn(text).map(m => m.group(1) -> Option(m.group(3)).getOrElse(m.group(2))).toMap

  /** Lines are quoted into a report, so cap them, drop control characters and drop query strings. */
  def sample(text: String): String =
    Control.replaceAllIn(Path.replaceAllIn(text, m => s"""path="${m.group(1)}""""), " ").take(300)

  def classify(m: LogMessage): List[Event] =
    if m.source == "heroku" && m.proc == "router" then router(m.text)
    else if m.source == "heroku" then dyno(m.text)
    else if m.source == "app" then app(m.text)
    else Nil

  private def observed(signal: Signal, text: String) = Event.Observed(signal, sample(text))

  private def router(text: String): List[Event] =
    val f = logfmt(text)
    val status = f.get("status").flatMap(_.toIntOption)
    val failure = f.get("at").filter(_ == "error").flatMap(_ => f.get("code")).map:
      case "H10" => Signal.Crash
      case "H12" => Signal.Timeout
      case _     => Signal.RouterError
    val serverError = status.filter(_ >= 500).map(_ => Signal.ServerError)
    val seen = Option.when(f.contains("method"))(Event.Request).toList
    seen ++ failure.orElse(serverError).map(observed(_, text)).toList

  private def dyno(text: String): List[Event] =
    text match
      case Memory("R15")                      => List(observed(Signal.Crash, text))
      case Memory(_)                          => List(observed(Signal.Memory, text))
      case Crashed()                          => List(observed(Signal.Crash, text))
      case Exit(code) if code != "0" && code != "143" => List(observed(Signal.Crash, text))
      case _                                  => Nil

  private def app(text: String): List[Event] =
    if JsonError.findFirstIn(text).isDefined || TextError.findFirstIn(text).isDefined then List(observed(Signal.AppError, text)) else Nil
