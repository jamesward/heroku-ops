package herokuops

import zio.Chunk
import zio.schema.{Schema, derived}
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec

import java.time.Instant

/** The parts of Heroku's app webhook payloads that heroku-ops reads. Fields it ignores are skipped. */
final case class HookHead(resource: String, action: String) derives Schema
final case class ReleaseData(version: Int, status: String, description: Option[String]) derives Schema
final case class ReleaseHook(data: ReleaseData) derives Schema
final case class StateData(status: Option[String], state: Option[String]) derives Schema
final case class StateHook(data: StateData) derives Schema

enum HookResult derives CanEqual:
  case Observed(signal: Signal, sample: String)
  case Released(release: Release)
  /** A dyno reported `crashed`. A release's dyno cycle (a deploy, an add-on config change) reports this too, so it isn't a crash until
    * [[Detector.dynoCrashed]] has checked for a release around it. */
  case DynoCrashed

object Webhook:
  private def decode[A: Schema](body: Array[Byte]): Either[String, A] =
    schemaBasedBinaryCodec[A].decode(Chunk.fromArray(body)).left.map(_.toString)

  def parse(body: Array[Byte], at: Instant): Either[String, List[HookResult]] =
    decode[HookHead](body).flatMap: head =>
      head.resource match
        case "release" =>
          decode[ReleaseHook](body).map(_.data).map: r =>
            val what = s"release v${r.version} ${r.status}: ${Classifier.sample(r.description.getOrElse(""))}"
            r.status match
              case "failed"    => List(HookResult.Observed(Signal.ReleaseFailed, what))
              case "succeeded" => List(HookResult.Released(Release(r.version, Classifier.sample(r.description.getOrElse("")).take(120), at)))
              case _           => Nil
        case "build" =>
          decode[StateHook](body).map(_.data).map: d =>
            if d.status.contains("failed") then List(HookResult.Observed(Signal.BuildFailed, "build failed")) else Nil
        case "dyno" =>
          decode[StateHook](body).map(_.data).map: d =>
            if d.state.contains("crashed") then List(HookResult.DynoCrashed) else Nil
        case _ => Right(Nil)
