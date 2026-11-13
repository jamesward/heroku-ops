package herokuops

import zio.*
import zio.http.*

import java.time.Instant

enum Outcome derives CanEqual:
  case Accepted, UnknownApp, Unauthorized

final case class Settings(factoryUrl: String, opsSecret: String)

object Settings:
  val DefaultFactoryUrl = "https://raw.githubusercontent.com/jamesward/projects/main/FACTORY.md"

  val live: ZLayer[Any, String, Settings] = ZLayer.fromZIO:
    for
      secret <- System.env("OPS_SECRET").orDie.someOrFail("OPS_SECRET is not set")
      url <- System.env("FACTORY_URL").orDie
    yield Settings(url.getOrElse(DefaultFactoryUrl), secret)

/** Receives what the apps send, keeps the rolling counters and fires an app's routine when the detector says so. */
final case class Ops(state: Ref[OpsState], client: Client, settings: Settings, ownUrl: Ref[Option[String]]):
  private def lookup(param: Option[String]): UIO[Option[(AppName, Watched)]] =
    state.get.map(s => param.flatMap(AppName.parse(_).toOption).flatMap(a => s.watched.get(a).map(a -> _)))

  def drain(appParam: Option[String], auth: Option[String], body: Array[Byte]): UIO[Outcome] =
    lookup(appParam).flatMap:
      case None => state.update(_.bump(s => s.copy(rejected = s.rejected + 1))).as(Outcome.UnknownApp)
      case Some((app, _)) if !Secrets.drainAuthorized(auth, Secrets.derive(settings.opsSecret, Secrets.Kind.Drain, app)) =>
        ZIO.logWarning(s"drain rejected app=${app.value}") *> state.update(_.bump(s => s.copy(rejected = s.rejected + 1))).as(Outcome.Unauthorized)
      case Some((app, _)) =>
        val messages = LogFrames.parse(body)
        val events = messages.flatMap(Classifier.classify)
        Clock.instant.flatMap: at =>
          state.update: s =>
            s.record(app, events, at).copy(lastLog = s.lastLog.updated(app, at))
              .bump(t => t.copy(drainBatches = t.drainBatches + 1, logLines = t.logLines + messages.size))
        .as(Outcome.Accepted)

  def hook(appParam: Option[String], signature: Option[String], body: Array[Byte]): UIO[Outcome] =
    lookup(appParam).flatMap:
      case None => state.update(_.bump(s => s.copy(rejected = s.rejected + 1))).as(Outcome.UnknownApp)
      case Some((app, _)) if !Secrets.webhookAuthorized(body, signature, Secrets.derive(settings.opsSecret, Secrets.Kind.Hook, app)) =>
        ZIO.logWarning(s"webhook rejected app=${app.value}") *> state.update(_.bump(s => s.copy(rejected = s.rejected + 1))).as(Outcome.Unauthorized)
      case Some((app, _)) =>
        Clock.instant.flatMap: at =>
          Webhook.parse(body, at) match
            case Left(why) => ZIO.logWarning(s"webhook not understood app=${app.value}: ${why.take(200)}").as(Outcome.Accepted)
            case Right(results) =>
              val events = results.collect { case HookResult.Observed(sig, sample) => Event.Observed(sig, sample) }
              val release = results.collectFirst { case HookResult.Released(r) => r }
              val dynoCrashed = results.contains(HookResult.DynoCrashed)
              state.update: s =>
                val recorded = release.fold(s.record(app, events, at))(s.record(app, events, at).released(app, _))
                (if dynoCrashed then recorded.dynoCrashed(app, at) else recorded).bump(t => t.copy(hooks = t.hooks + 1))
              .as(Outcome.Accepted)

  /** Requests the app's probe path once. A 5xx, a timeout or a connection error counts as down. */
  def probe(w: Watched): UIO[ProbeResult] =
    for
      started <- Clock.nanoTime
      status <- ZIO.fromEither(URL.decode(w.url + w.probe)).flatMap: url =>
        client.batched(Request.get(url)).timeoutFail(new RuntimeException("probe timed out"))(10.seconds).map(r => Some(r.status.code))
      .catchAllCause(c => ZIO.logDebugCause(s"probe failed app=${w.app.value}", c).as(None))
      ended <- Clock.nanoTime
      at <- Clock.instant
    yield ProbeResult(at, status.exists(_ < 500), status, (ended - started) / 1000000)

  /** Probes every watched app once. */
  def probeAll: UIO[Unit] =
    state.get.flatMap: s =>
      ZIO.foreachParDiscard(s.watched.values): w =>
        probe(w).flatMap(r => state.update(x => x.probed(w.app, r.ok).copy(lastProbe = x.lastProbe.updated(w.app, r))))

  def refreshConfig: UIO[Unit] =
    val fetch = for
      url <- ZIO.fromEither(URL.decode(settings.factoryUrl))
      response <- client.batched(Request.get(url))
      text <- response.body.asString
      watched <- if response.status.isSuccess then ZIO.fromEither(FactoryTable.watched(text)).mapError(new RuntimeException(_))
                 else ZIO.fail(new RuntimeException(s"HTTP ${response.status.code}"))
    yield watched
    fetch.flatMap: watched =>
      Clock.instant.flatMap: at =>
        state.update(s => s.copy(watched = watched.map(w => w.app -> w).toMap, configLoadedAt = Some(at)))
    .catchAllCause(c => ZIO.logWarningCause("could not refresh the watched apps; keeping the last good list", c))

  /** While an undelivered incident is recent, requests this app's own public `/alarm` (503), through the Heroku router, so the app's
    * failed-request alert fires (see `Alarm`). The address is the one the first request arrived on (`OpsRoutes.routes`). `active` holds whether the last tick had the alarm on, to log only the changes. */
  def alarmTick(active: Ref[Boolean]): UIO[Unit] =
    for
      s <- state.get
      now <- Clock.instant
      late = Alarm.active(s, now)
      on = late.nonEmpty
      url <- ownUrl.get
      was <- active.getAndSet(on)
      _ <- ZIO.logWarning(s"alarm on: ${late.map(l => s"${l.app} (${l.why})").mkString("; ")}" +
        (if url.isEmpty then "; no request has told this app its own address yet, so nothing requests /alarm" else "")).when(on && !was)
      _ <- ZIO.logInfo("alarm off").when(!on && was)
      _ <- ZIO.foreachDiscard(url.filter(_ => on)): base =>
        ZIO.fromEither(URL.decode(base + "/alarm")).flatMap(url => client.batched(Request.get(url)))
          .timeout(5.seconds).ignore
    yield ()

  private def tokenVar(app: AppName) = s"FIRE_TOKEN_${app.value.toUpperCase.replace('-', '_')}"

  /** Evaluates every app and fires the routine for each alert. */
  def evaluateAll: UIO[Unit] =
    Clock.instant.flatMap: at =>
      state.modify: s0 =>
        val s = s0.settle(at)
        val results = s.apps.map((name, st) => (name, Detector.evaluate(name, st, at)))
        val alerts = results.values.flatMap(_._2).toList
        (alerts, s.copy(apps = results.map((n, r) => n -> r._1)))
      .flatMap(alerts => ZIO.foreachDiscard(alerts)(fire(_, at)))

  private def record(alert: Alert, delivery: Delivery): UIO[Unit] =
    state.update(_.incident(IncidentRecord(alert.at, alert.app, alert.reasons, alert.samples, delivery)))

  private def fire(alert: Alert, at: Instant): UIO[Unit] =
    state.get.flatMap: s =>
      s.watched.get(alert.app) match
        case None => ZIO.unit
        case Some(w) =>
          s.openIncident(alert.app, at) match
            case Some(open) =>
              ZIO.logInfo(s"added to open incident ${open.id}: ${alert.reasons.map(r => s"${r.signal} x${r.count}").mkString(", ")}") *>
                state.update(_.fold(open, alert))
            case None if !s.mayFire(at) => unreached(alert, "global fire limit reached")
            case None                   => deliver(alert, w, s, at)

  private def deliver(alert: Alert, w: Watched, s: OpsState, at: Instant): UIO[Unit] =
    val text = IncidentText.build(alert, w, s.releases.get(alert.app).flatMap(_.headOption))
    System.env(tokenVar(alert.app)).orDie.flatMap: token =>
      (w.routine, token) match
        case (Some(id), Some(t)) =>
          Fire.send(id, t, text).provide(ZLayer.succeed(client)).foldZIO(
            why => state.update(_.bump(x => x.copy(fireFailures = x.fireFailures + 1))) *> unreached(alert, s"fire failed: $why"),
            session => ZIO.logInfo(s"fired routine app=${alert.app.value} session=$session") *>
              state.update(x => x.bump(y => y.copy(fires = y.fires + 1)).copy(fireTimes = at :: x.fireTimes.take(50))) *>
              record(alert, Delivery.Fired(session)),
          )
        case (None, _) => unreached(alert, s"no routine id for ${w.repo} in FACTORY.md")
        case (_, None) => unreached(alert, s"no ${tokenVar(alert.app)} config var")

  /** An incident the routine couldn't be told about is logged and recorded; `Alarm` makes the rest of the fallback. */
  private def unreached(alert: Alert, why: String): UIO[Unit] =
    ZIO.logError(s"incident not delivered app=${alert.app.value} signals=${alert.reasons.map(_.signal)} why=$why") *>
      record(alert, Delivery.Undelivered(why))

object Ops:
  val live: ZLayer[Client & Settings, Nothing, Ops] = ZLayer.fromZIO:
    for
      state <- Ref.make(OpsState.empty)
      client <- ZIO.service[Client]
      settings <- ZIO.service[Settings]
      ownUrl <- Ref.make(Option.empty[String])
    yield Ops(state, client, settings, ownUrl)
