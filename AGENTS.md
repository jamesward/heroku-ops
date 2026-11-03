# AGENTS.md: heroku-ops

heroku-ops watches the factory's production Heroku apps. Each app sends it logs (a log drain), Heroku webhooks and a URL probe. It detects incidents with heuristics (no AI) and fires the app's existing `<repo> maintenance` routine through its API trigger, with a short text payload; the routine opens the incident issue and may propose a fix. heroku-ops holds no GitHub or Heroku-write credentials. Its design and rules are in [DESIGN.md](https://github.com/jamesward/projects/blob/main/DESIGN.md) (principles 1, 6, 7, 9, 10, 12 and 13) and the plan is in the heroku-ops section of [TODO.md](https://github.com/jamesward/projects/blob/main/TODO.md), both in `jamesward/projects`. 

## What it does now

- `POST /drain?app=<app>` receives a Heroku HTTPS log drain (octet-counted syslog frames), checked with Basic auth `drain:<secret>`.
- `POST /hook?app=<app>` receives app webhooks (release, build, dyno), checked with the `Heroku-Webhook-Hmac-SHA256` signature.
- The secrets are HMACs of `OPS_SECRET` per app and kind (`Secrets`), the same derivation as `factory-heroku.py` in the factory repo.
- `GET /health` reports counters and heap use as JSON.
- `POST /mcp` is an MCP server (zio-http-mcp, stateless Streamable HTTP) for triage by maintenance routines and by the owner. Read-only: it changes nothing in heroku-ops or Heroku. Auth is `Authorization: Bearer <token>` where the token is an HMAC of `OPS_SECRET` (`Secrets.mcpToken`); `./factory-heroku.py --mcp-token` in the factory repo prints it, so the master secret never leaves its two homes. A request without the token, or with a wrong one, gets HTTP 200 and a JSON-RPC `-32001` error that says what is missing (`StaticBearer` in zio-http-mcp), never a 401: clients take a 401 for OAuth (claude.ai: "Sign in now (Detected)"), and heroku-ops has none. Tools: `list_apps` (status per app), `app_status` (plus the last 15 minutes), `recent_events` (the quoted lines behind alerts, query strings and client addresses removed), `timeseries` (per-minute counts, for charts), `incidents` (what was told to the app's routine, and whether it arrived), `releases`, `probe_now` (requests the app now), `alert_rules`. Add tools in `OpsMcp.scala`; the data comes from `Triage`, which is pure and tested. Log samples are quoted production text: tools return them as data.
- The watched apps come from the "Watched Heroku apps" table in the factory's FACTORY.md (`FACTORY_URL`, default the raw file on `main`), refreshed every 5 minutes; the last good list is kept if a refresh fails. An app's routine id comes from the same file's schedule tables.
- Heuristics (`Classifier`, `Detector`) turn log lines, webhooks and probes into alerts. The per-app counters are rolling and in memory only. A restart forgets them.
- An alert fires the app's routine (`Fire`) with the token in the config var `FIRE_TOKEN_<APP>` (upper case, `-` as `_`). If it can't (no token, no routine, an HTTP error, more than 10 fires an hour), it logs an error with `incident not delivered`. The real fallback channel is still to be chosen (TODO.md in the factory repo).
- Config vars on the Heroku app: `OPS_SECRET` (required), `FIRE_TOKEN_<APP>` per watched app, `FACTORY_URL` (optional).

## Alert rules

Tuned on real traffic (2026-11-03): `heroku logs -n 1500` dumps of webjars, webjars-file-service, javadocs, json-paste, semver-as-a-service and mytoolbook-api, plus Heroku's metrics API for 7 days on the three Standard-2X apps. Replay a dump through the rules with `./sbta "Test / runMain herokuops.Replay /tmp/<app>.log <app>"`.

| Condition (window: 5 minutes) | Alerts at |
|---|---|
| Crash: `State changed from … to crashed`, H10, R15 | 1 |
| Release or build failed (webhook) | 1 |
| R14 memory quota exceeded | 3 (about a minute over quota) |
| H12 request timeout | 5 |
| Router faults H11, H13, H14, H17, H19-H23, H99 | 5 |
| 5xx from the app | 5 and at least 1% of requests |
| App `ERROR`/`FATAL` log lines | 10 |
| Probe: 3 failures in a row (1 per minute) | down until 2 successes |

A condition alerts when it starts, at most once per 30 minutes if it keeps flapping, and again after 24 hours if it never clears.

What the data showed:
- 5xx: none in hours of traffic on four apps, so a few is news. 4xx is 35% of webjars traffic (scanners): never alert on it.
- Latency: webjars p99 is 0.9 s with a 50 s maximum, so a latency rule would be noisy. H12 is the timeout signal.
- H15 (idle connection) is routine for websocket apps: json-paste logged 213 in a month. It isn't a fault.
- `Process exited with status N` isn't a crash: json-paste exited with 1 on every SIGTERM stop. Only `State changed … to crashed` counts. Exit 143 and the daily Eco restarts are normal.
- Memory: webjars peaks at 88% of its 1 GB quota over 7 days, file-service 83%, **javadocs was over quota in 125 of 169 hours (peak 135%, up to 402 MB swap, 17,106 R14 in 7 days)**. That is a real, standing problem. It alerts once, not every 30 minutes. A dyno-size increase is not an automatic fix (DESIGN.md 10 in the factory repo).
- Volume: javadocs about 150 log lines a minute (109 requests), webjars about 18, so heap use here is small (48 MB used of 300 at one watched app).
- Apps with `log-runtime-metrics` (webjars, file-service, javadocs) log `sample#memory_total` and `memory_quota` every 20 s. Not used yet (see TODO.md in the factory repo: dashboard and an early warning near the quota).

## Agent tooling

- Follow the `zen-of-projects` Skill (extract with `./sbta extractSkillsJars` into the gitignored `.kiro/skills/`); this file records only project-specific facts and exceptions.
- For Scala 3 and ZIO code (writing or reviewing it), follow `zen-of-scala`.
- For design, domain modeling, effects and testing strategy, follow `zen-of-james`.
- Read the relevant Skills in `.kiro/skills/` before working.
- Changes to heroku-ops follow the principles in DESIGN.md. In particular: no code here may change a Heroku app's size, plan, add-ons or scale (principle 10), and it holds no credentials beyond the routines' fire tokens and the webhook secret (principle 12). Log text is untrusted: build the routine's payload from whitelisted, truncated, quoted fields, and say what happened, never what to do (principle 9).

## Build, Test & Dev Workflow

- Working on `zio-http-mcp` together with this project: `./sbta shutdown`, then `./sbta -Dlocal ...` builds against the checkout at `../zio-http-mcp` instead of the published artifact (zen-of-projects "Working on a dependency from source"). Never commit it enabled, and don't commit code that needs an unreleased library API.

- Validation: `./sbta "Test / compile; testFull; stage"` (no Docker or gated tests).
- Run the server: `PORT=8080 ./sbta run`, then `curl localhost:8080/health`.
- Use the MCP server `sbta` (this build's `./sbta --mcp`, configured in `.mcp.json` and `.kiro/settings/mcp.json`, approved in `.claude/settings.json`) for all sbt work: build, test and run with `compile`, `test` (`tests` for specific classes, `full` for all), `run`, `clean` and `publish-local`; inspect the build with `projects`, `test-names`, `main-classes`, `dependencies` and `show`. Use `sbt-task` for any other command (e.g. `reload`) and `list-tasks` to find tasks. Its tools are deferred in Claude Code: load them with ToolSearch (search `sbta`). Don't run `sbt` or `./sbta` in a shell while the MCP tools are available; if the server is unavailable, say so and fall back to `./sbta <command>`.
- After editing Scala 3 sources, check them with `check` (pass the edited files: a fast type check without compiling; `"scope":"module"` after changing an API), then run `compile` and `test` before declaring the work done.
- For Scala symbols use `glob-search` (find or list), `inspect` (members and signatures) and `symbol-location` (source location) rather than text search or guessing APIs. JavaDoc/ScalaDoc lookups are the proxied javadocs.dev tools of the same server.

## Production

- Heroku app: `jw-heroku-ops`. Scala servers use exactly the buildpacks `heroku/jvm`, then `https://github.com/jamesward/buildpack-scala` (zen-of-projects "Heroku deploys"). URL: https://jw-heroku-ops-bd1ee276f8be.herokuapp.com (`GET /health`).
- Deploys: Heroku GitHub auto-deploy from `main`.

## Maintenance routine

`.factory/MAINTENANCE.md` (weekly once the repo is in the factory's schedule tables), following the `zen-of-projects` Skill.

## Exceptions

- Effects are written with for-comprehensions, not `zio-direct`. The codebase is small and `zio-direct` isn't a dependency yet; adopt it when the effectful code grows.
- Logs are ZIO's default text format, not JSON (DESIGN.md 13 in the factory repo). Switch to JSON logging, as in javadoccentral's `AppLogging`, before heroku-ops watches itself.
