package herokuops

import zio.*
import zio.logging.{ConsoleLoggerConfig, LogFilter, LogFormat, consoleJsonLogger}
import zio.logging.slf4j.bridge.Slf4jBridge

/** The logging bootstrap (zen-of-projects "Production Servers"; modeled on javadoccentral's `AppLogging`).
  *
  * One JSON object per event: `timestamp`, `level`, `thread`, `message`, `cause` when there is one, and every `ZIO.logAnnotate`
  * key as its own field (`request_id` among them, see `OpsRoutes.requestId`). Heroku's logplex splits on real newlines, so a
  * stack trace has to stay inside one JSON string, where the newlines are escaped. The SLF4J bridge is the only slf4j provider, so
  * Netty and the other Java libraries log through ZIO in the same format. The floor is INFO. */
object AppLogging:
  val json: ZLayer[Any, Nothing, Unit] =
    val config = ConsoleLoggerConfig(format, LogFilter.LogLevelByNameConfig(LogLevel.Info, Map.empty))
    Runtime.removeDefaultLoggers >>> consoleJsonLogger(config) >>> Slf4jBridge.init(LogFilter.logLevelByName[Any](LogLevel.Info))

  // `lazy` because `json` reads it while the object is being initialized (a plain val declared after it would still be null).
  /** `LogFormat.default` without the padding of the timestamp (it would leak trailing spaces into the JSON value), plus the annotations. */
  private lazy val format: LogFormat =
    import LogFormat.*
    label("timestamp", timestamp) |-|
      label("level", level) |-|
      label("thread", fiberId) |-|
      label("message", quoted(line)) +
      (space + label("cause", cause)).filter(LogFilter.causeNonEmpty) +
      annotations
