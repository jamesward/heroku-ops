package herokuops

import java.nio.charset.StandardCharsets.UTF_8
import scala.annotation.tailrec

/** One syslog message from a Heroku HTTPS log drain: `source` is `heroku` or `app`, `proc` is `router`, `web.1`, ... */
final case class LogMessage(source: String, proc: String, text: String)

object LogFrames:
  private val Header = raw"(?s)^<\d+>\d+ \S+ \S+ (\S+) (\S+) \S+ (.*)$$".r

  /** Frames are octet counted: `<byte count> <syslog message>`, back to back. Malformed input ends the parse. */
  def parse(body: Array[Byte]): List[LogMessage] =
    @tailrec def go(from: Int, acc: List[LogMessage]): List[LogMessage] =
      val space = body.indexOf(' '.toByte, from)
      val length = if space > from then new String(body, from, space - from, UTF_8).toIntOption else None
      length match
        case Some(n) if n >= 0 && space + 1 + n <= body.length =>
          val msg = new String(body, space + 1, n, UTF_8)
          val next = msg match
            case Header(source, proc, text) => LogMessage(source, proc, text.stripTrailing) :: acc
            case _                          => acc
          go(space + 1 + n, next)
        case _ => acc.reverse
    go(0, Nil)
