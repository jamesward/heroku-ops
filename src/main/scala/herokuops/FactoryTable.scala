package herokuops

/** The watched apps and routine ids, read from the factory's FACTORY.md (the one source of truth). */
object FactoryTable:
  private val RoutineRow = raw"^\|\s*`([^`]+)`\s*\|.*`(trig_[A-Za-z0-9]+)`\s*\|\s*$$".r

  def routines(md: String): Map[String, RoutineId] =
    md.linesIterator.flatMap:
      case RoutineRow(repo, id) => RoutineId.parse(id).toOption.map(repo -> _)
      case _                    => None
    .toMap

  def watched(md: String): Either[String, List[Watched]] =
    val ids = routines(md)
    val rows = md.linesIterator
      .dropWhile(!_.startsWith("### Watched Heroku apps"))
      .drop(1)
      .takeWhile(!_.startsWith("#"))
      .filter(l => l.startsWith("|") && !l.matches(raw"^\|\s*-.*") && !l.startsWith("| App"))
      .toList
    val parsed = rows.map: line =>
      line.stripPrefix("|").stripSuffix("|").split("\\|", -1).map(_.trim.stripPrefix("`").stripSuffix("`")).toList match
        case app :: repo :: url :: probe :: Nil =>
          AppName.parse(app).flatMap: name =>
            Either.cond(url.startsWith("https://") && probe.startsWith("/"), Watched(name, repo, url, probe, ids.get(repo)), s"$app: bad URL or probe")
        case _ => Left(s"row needs 4 cells: $line")
    parsed.collectFirst { case Left(e) => e }.toLeft(parsed.collect { case Right(w) => w })
