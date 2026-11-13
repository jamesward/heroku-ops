package herokuops

/** Heuristics that turn a log line into events. Detection is patterns and counts, not AI. */
object Classifier:
  private val Pair = raw"""(\w+)=("([^"]*)"|\S*)""".r
  private val Crashed = raw"State changed from \w+ to crashed".r.unanchored
  private val Memory = raw"Error (R1[45]) ".r.unanchored
  private val JsonLevel = raw""""level"\s*:\s*"(\w+)"""".r.unanchored
  private val TextError = raw"\b(ERROR|FATAL)\b".r.unanchored
  private val Path = raw"""path="([^"?]*)\?[^"]*"""".r
  private val Fwd = raw"""fwd="[^"]*"""".r
  private val Control = raw"[\p{Cntrl}&&[^\t]]".r

  /** Router errors that mean the app can't serve. The rest (H15 idle connection on a websocket, H18 and H27 a client that
    * left, H25, H31) are routine for apps with websockets or crawlers: json-paste logs hundreds of H15 a month. */
  private val RouterFaults = Set("H11", "H13", "H14", "H17", "H19", "H20", "H21", "H22", "H23", "H99")

  def logfmt(text: String): Map[String, String] =
    Pair.findAllMatchIn(text).map(m => m.group(1) -> Option(m.group(3)).getOrElse(m.group(2))).toMap

  /** Lines are quoted into a report, so cap them, drop control characters and drop query strings. */
  def sample(text: String): String =
    Control.replaceAllIn(Fwd.replaceAllIn(Path.replaceAllIn(text, m => s"""path="${m.group(1)}""""), "fwd=-"), " ").take(300)

  def classify(m: LogMessage): List[Event] =
    if m.source == "heroku" && m.proc == "router" then router(m.text)
    else if m.source == "heroku" then dyno(m.text)
    else if m.source == "app" then app(m.text)
    else Nil

  private def observed(signal: Signal, text: String) = Event.Observed(signal, sample(text))

  private def router(text: String): List[Event] =
    val f = logfmt(text)
    val status = f.get("status").flatMap(_.toIntOption)
    val failure = f.get("at").filter(_ == "error").flatMap(_ => f.get("code")).flatMap:
      case "H10"                 => Some(Signal.Crash)
      case "H12"                 => Some(Signal.Timeout)
      case c if RouterFaults(c)  => Some(Signal.RouterError)
      case _                     => None
    val serverError = status.filter(_ >= 500).map(_ => Signal.ServerError)
    val seen = Option.when(f.contains("method"))(Event.Request).toList
    seen ++ failure.orElse(serverError).map(observed(_, text)).toList

  /** `Process exited with status N` isn't a crash by itself: json-paste exited with 1 on every SIGTERM stop (idling, deploys). Heroku
    * writes `State changed from up to crashed` when a process dies unexpectedly. */
  private def dyno(text: String): List[Event] =
    text match
      case Memory("R15")                      => List(observed(Signal.Crash, text))
      case Memory(_)                          => List(observed(Signal.Memory, text))
      case Crashed()                          => List(observed(Signal.Crash, text))
      case _                                  => Nil

  /** A structured (JSON) line is an error by its own `level` only: a WARN whose cause quotes Postgres' `FATAL: …` isn't one. Plain text lines
    * have no level field, so for them the words ERROR or FATAL count. */
  private def app(text: String): List[Event] =
    val isError = JsonLevel.findFirstMatchIn(text) match
      case Some(m) => m.group(1).equalsIgnoreCase("ERROR") || m.group(1).equalsIgnoreCase("FATAL")
      case None    => TextError.findFirstIn(text).isDefined
    if isError then List(observed(Signal.AppError, text)) else Nil
