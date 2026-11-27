package herokuops

import zio.*
import zio.http.*
import zio.stream.ZStream

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/** The end of a failed build's output, kept with its `buildfailed` event (projects#68). The webhook's `output_stream_url` is a signed URL
  * on Heroku's build output host, readable without a Heroku credential, so reading it keeps DESIGN.md 12. */
final case class BuildOutput(at: Instant, commit: Option[String], lines: List[String])

object BuildOutput:
  val MaxLines = 50
  val MaxChars = 4000
  /** Only this much of the end of the stream is kept while it is read. */
  val TailBytes = 64 * 1024
  val Host = "build-output.heroku.com"

  private val Sha = "[0-9a-f]{7,40}".r
  private val Ansi = "\u001b\\[[0-9;?]*[ -/]*[@-~]".r

  /** `source_blob.version`: a commit for GitHub and git-push builds. Anything else (an API build's label) is quoted like a log line. */
  def commit(version: String): String = if Sha.matches(version) then version else Classifier.sample(version).take(64)

  def sample(commit: Option[String]): String = commit.fold("build failed")(c => s"build failed at $c")

  /** Only an HTTPS URL on Heroku's build output host is read. */
  def allowed(url: String): Option[URL] =
    URL.decode(url).toOption.filter(u => u.scheme.contains(Scheme.HTTPS) && u.host.contains(Host))

  /** The last lines of `tail`, the end of the output: colors removed, each line quoted like a log sample, at most [[MaxLines]] lines and
    * [[MaxChars]] characters. `complete` is false when `tail` starts inside the output, so its first, partial line is dropped. */
  def lines(tail: String, complete: Boolean): List[String] =
    val all = tail.split("\r?\n", -1).toList
    val quoted = (if complete then all else all.drop(1))
      .map(l => Classifier.sample(Ansi.replaceAllIn(l, "")).strip).filter(_.nonEmpty).takeRight(MaxLines)
    val newestFirst = quoted.reverse
    val used = newestFirst.scanLeft(0)(_ + _.length + 1).tail
    newestFirst.zip(used).takeWhile(_._2 <= MaxChars).map(_._1).reverse

  /** Reads the stream for at most 20 s, keeping its last [[TailBytes]]. A failure gives no lines; the URL is never logged (it is signed). */
  def fetch(client: Client, url: URL): UIO[List[String]] =
    client.stream(Request.get(url)): r =>
      if r.status.isSuccess then r.body.asStream.chunks
      else ZStream.fromZIO(ZIO.logWarning(s"build output: HTTP ${r.status.code}")).drain
    .interruptAfter(20.seconds)
    .runFold((Chunk.empty[Byte], true)):
      case ((kept, complete), chunk) =>
        val all = kept ++ chunk
        if all.size > TailBytes then (all.takeRight(TailBytes), false) else (all, complete)
    .map((bytes, complete) => lines(String(bytes.toArray, UTF_8), complete))
    .timeout(30.seconds).map(_.getOrElse(Nil))
    .catchAllCause(c => ZIO.logWarning(s"build output not read: ${c.squash.getClass.getSimpleName}").as(Nil))
