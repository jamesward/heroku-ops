package herokuops

import zio.json.*
import zio.json.ast.Json

/** The sample of an app's JSON log line (DESIGN.md 13: one object per line, the stack trace in `cause`, annotations as top-level fields),
  * built from chosen fields instead of the raw prefix (#26). zio-logging writes `cause` before the annotations, so the first 300
  * characters of a line end inside the stack trace, before the fields that say which request or tool failed.
  *
  * The text is quoted into a routine's payload (DESIGN.md 9), so: the convention's fields, each capped; the first line of the stack trace
  * and its first frame; an annotation's value only when it is a short identifier (`tool=get_javadoc_symbol`, `durationMs=41000`) and its
  * name doesn't suggest an address or a secret; and only the names of the rest (`params`, `client_ip`). */
object JsonLine:
  private val Known = Set("timestamp", "level", "message", "error", "cause", "stack_trace", "request_id", "thread")
  private val Identifier = raw"[A-Za-z0-9_.:/@+-]{1,40}".r
  private val Sensitive = raw"(?i).*(ip|addr|host|fwd|forward|token|secret|pass|key|auth|cookie|session|mail|user|phone).*".r

  def sample(text: String): Option[String] =
    text.fromJson[Json.Obj].toOption.map: obj =>
      val fields = obj.fields.toList
      def str(name: String) = fields.collectFirst { case (`name`, Json.Str(v)) => v }
      val trace = str("cause").orElse(str("stack_trace")).map(_.split('\n').toList.map(_.trim).filter(_.nonEmpty))
      val cause = trace.flatMap(_.headOption).map(c => s"cause: ${c.take(160)}")
      val frame = trace.flatMap(_.drop(1).find(_.startsWith("at "))).map(_.take(120))
      val others = fields.filterNot((k, _) => Known(k))
      val (shown, named) = others.partitionMap: (k, v) =>
        scalar(v).filter(s => !Sensitive.matches(k) && Identifier.matches(s)).map(s => s"$k=$s").toLeft(k)
      List(
        str("level"),
        str("message").map(m => s"\"${m.take(120)}\""),
        str("error").map(e => s"error: ${e.take(120)}"),
        cause,
        frame,
        str("request_id").filter(Identifier.matches).map(r => s"request_id=$r"),
        Option.when(shown.nonEmpty)(shown.mkString(" ")),
        Option.when(named.nonEmpty)(s"fields: ${named.mkString(", ")}"),
        str("timestamp").filter(Identifier.matches),
      ).flatten.mkString(" ")

  private def scalar(v: Json): Option[String] = v match
    case Json.Str(s)  => Some(s)
    case Json.Num(n)  => Some(n.toString)
    case Json.Bool(b) => Some(b.toString)
    case _            => None
