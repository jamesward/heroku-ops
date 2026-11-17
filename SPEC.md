# The factory as a ZIO app

Status: draft 10 for the owner's review, 2026-11-13. Built so far: `zio-auth-server` 0.0.2, with toolbook's `login` running on it in production (5.14, phase 1 of section 13). Nothing else is built yet. Section 14 lists the measurements, documented facts and experiments this draft rests on.

Draft 10 adds Skills as their own part of the design (5.18): Skills come from pluggable providers (SkillsJars, skills.sh), each project pins the ones it uses, every session records exactly which Skill text it ran with, and the factory's own guidance is a Skill in `jamesward/skills`. It also records what the `zio-auth-server` extraction actually produced (5.14).

The factory (`jamesward/projects`) runs as Claude Code routines today, driven by Python scripts and configured in FACTORY.md. This spec replaces all three:
- **A ZIO app on Heroku is the factory.** It holds the configuration in Postgres, schedules work, receives webhooks and ops events, applies policy, and does every GitHub write. It is reachable only through its MCP server.
- **Agent sessions run in sandboxes.** A sandbox is a pluggable compute substrate, first an AWS Lambda MicroVM (a Firecracker VM per session, with Docker). A runner in it drives kiro-cli over ACP. The session holds no GitHub write credential: it works through the factory's session gateway.
- **Ops sources are pluggable.** `jw-heroku-ops` is the first. It detects and reports, and the factory decides what to do.

Its rules are DESIGN.md in `jamesward/projects`. Where this spec changes a principle, section 11 says how. "Today" means the factory as FACTORY.md describes it on 2026-11-12.

## Decisions so far

These are the owner's decisions of 2026-11-12:
- **ZIO for all of it.** The `*.py` scripts are ported to the ZIO app.
- **Bail on Claude routines.** Agent sessions are kiro-cli on a dedicated Kiro subscription.
- **Model:** set in configuration per job kind and per repo. The default is `claude-opus-5.5`.
- **Repository:** this repo stays `jamesward/heroku-ops` for now. The factory's modules are built here.
- **Postgres is the source of truth.** FACTORY.md goes away. One Postgres database serves the factory and its authorization server.
- **The MCP server is the only interface,** used from claude.ai (a custom connector) and local clients. It replaces the owner's manual "create a repo, grant the App access, open Claude Code" flow (5.15).
- **Sign-in:**
  - The authorization server is `zio-auth-server`, a library extracted from toolbook's `login` (built: 0.0.2). Its persistence is pluggable: the factory plugs in direct database access, and toolbook plugs in its API layer (5.14).
  - The owner signs in with email (Mailgun) or a passkey.
  - GitHub is a separate OAuth connection, not a sign-in method.
- **Agent sessions need Docker,** and Testcontainers must work in every session, with no fallback that skips it. So sessions don't run on Heroku. The sandbox substrate is pluggable (5.6). The factory and the ops sources stay on Heroku.
- **Sandboxes are x86_64.**
- **Sessions keep dependency caches between runs** (Coursier, Maven, Gradle, npm, Docker images), so a fresh session doesn't download everything again (8.5).
- **Local development needs no external service:** no AWS, GitHub, Heroku, Mailgun or Kiro. It has a fake GitHub and a simulator for issues, CI, incidents and time (section 12).
- **Ops is pluggable** (5.5). An ops source reports to the factory, and the factory decides what to do.
- **Sessions talk back to the factory over MCP,** with a session identity scoped to one project (5.8).
- **The factory improves itself** through findings and proposals that the owner approves, since it no longer works through PRs to itself (5.12).
- **Later:** expose sessions as ACP servers (ACP over HTTP), so ACP clients such as Kiro Crew and IntelliJ can connect to the factory (section 10).

Added on 2026-11-13:
- **Skills are pinned,** in every project, including projects without a Maven, Gradle or sbt build (5.18).
- **The factory's own guidance is a Skill** (working name `zen-of-factory`) in `jamesward/skills`.
- **Projects without a JVM build pin Skills with a skillsjars CLI** (to be built, 5.18).
- **Skill providers are pluggable.** The owner's projects use SkillsJars, and the factory also supports others, starting with skills.sh.
- **How Skill changes are released and rolled out is open** (section 15): releases and keeping every project current are the pain today.

## 1. Why

Routines can't be managed from anywhere the factory runs:
- There is no public API for creating or updating routines.
- `/schedule` is unavailable in cloud sessions.
- `claude setup-token` tokens are refused by the routines API (`oauth_scope_insufficient`, tested 2026-11-12).
- Storing a claude.ai login in our own service is against Anthropic's terms.

Other limits come with routines too:
- Authority depends on a classifier.
- Runs act as the owner.
- Transcripts are unreadable from the cloud.
- API triggers can only be made in the web UI.

Running the factory ourselves removes all of these. The price is building and operating it.

## 2. Goals and non-goals

Goals:
1. **Everything the factory does today keeps working.** Section 4 maps each function to its replacement.
2. **One place for configuration (Postgres), changed through MCP,** with every change versioned and audited.
3. **Least privilege per session.** A session holds no GitHub write credential and no long-lived secret, and sees only its own project. Everything it changes outside its VM goes through a factory tool that checks it (5.8).
4. **Policy in code, not prompts.** The factory decides merges, pushes, incident actions and tool access from facts. The runner decides each tool call. Neither is left to the model.
5. **One interface:** the factory's MCP server, from claude.ai and local clients.
6. **Docker in every session,** so Testcontainers tests run before a PR exists.
7. **Bounded spend:** sandbox time, Kiro credits and run counts have caps set by the owner and enforced in code.
8. **Pluggable edges:** sandboxes, ops sources and the authorization server's persistence can each change without touching the factory's logic.
9. **A self-improvement loop** the owner controls: findings from sessions, measured outcomes, and proposals applied only on approval.

Non-goals:
- A web UI.
- Multi-tenancy. There is one owner, and the 5 GitHub owners are that owner's accounts and orgs.
- Changing what a maintenance run does. The zen-of-projects Skill still defines it, with its GitHub steps moved to factory tools and the factory-specific parts moved to `zen-of-factory` (section 11).

## 3. Overview

```
 owner ── claude.ai / Kiro / Claude Code ──MCP + OAuth──┐
                                                        ▼
 GitHub App webhooks ─────────────► jw-factory (Heroku; ZIO; control plane)
 ops sources (jw-heroku-ops, …) ──► config · scheduler · triggers · ops policy · merge policy
     signed ops events              session gateway (MCP for sessions; GitHub; MCP proxy)
     ◄── triage MCP (proxied) ───── owner MCP · auth server · improvement loop
                                    Postgres (one database)
                                                        │ Sandboxes (pluggable)
                                                        ▼
                         AWS Lambda MicroVMs (first), in a dedicated AWS account
                         one Firecracker VM per session:
                           runner (ZIO, ACP client) ── stdio ── kiro-cli acp (v3, client auth)
                           runner mcp (stdio) = local tools + the factory's session gateway
                           terminal commands as an unprivileged user, rootless Docker
                                                        │ outbound HTTPS only
                                                        └─► session gateway, Kiro, Maven Central, npm, Docker registries
```

| Part | Where | Module (this repo) | Holds |
|---|---|---|---|
| `jw-factory` | Heroku web dyno, with Heroku Postgres | `factory` (with `zio-auth-server`) | every factory secret (7.3) |
| `jw-heroku-ops` | Heroku (today's app) | `ops` | `OPS_SECRET` and its factory reporting key |
| Sandboxes | AWS Lambda MicroVMs in a dedicated AWS account | `runner`, plus the image definition | a session token and Kiro access tokens, in memory |
| shared | — | `core` (domain types, the ops event protocol, codecs) | — |

## 4. What it replaces

| Today | In the factory |
|---|---|
| FACTORY.md (schedule tables, watched apps, project notes) and `PROMPT` in `factory-routines-sync.py` | Configuration in Postgres, edited through MCP, versioned and audited (5.2). It is imported once from FACTORY.md, which is then deleted. |
| 50 `<repo> maintenance` routines, `factory-routines-sync.py` | `Maintenance` jobs scheduled from the configuration (5.3). Nothing to create or update. |
| The routines' saved prompt and merge authority (DESIGN 15) | A prompt per job kind in configuration, and the merge policy (5.10). |
| `factory-sync.py` + `factory_sbta.py` (`factory sync` routine) | The in-process `Sync` job (5.11). |
| `factory-heroku.py` (`heroku control` routine) | The ops source's control job (5.5): for heroku-ops, `HerokuControl` in-process. |
| `factory-github-settings.py` | The check runs inside `Sync`. Fixes are applied with `apply_repo_settings`, using the owner's GitHub connection (6.1). |
| `factory-land.sh`, `factory-repo-check.sh` | `onboard_repo` and `check_repo` (5.15). |
| `factory-transcript.py` (local only) | MCP `session` and `transcript`. |
| heroku-ops `Fire` with `FIRE_TOKEN_<APP>`, and its cooldowns and rate limit | heroku-ops sends signed ops events. The factory's ops policy decides (5.5). |
| Each run's step 8 `Factory improvement:` PRs to `jamesward/skills` and `jamesward/projects` | `report_finding`, clustering, the weekly review, and owner-approved proposals (5.12). |
| Hand-copied `.factory/skills/<name>/SKILL.md` to test an unreleased Skill, and the sync job removing stale copies | Skill overlays in the `SessionSpec` (5.18). |
| Sites and node repos reading `jamesward/skills` main from a clone | A pin like every other project, through the skillsjars CLI (5.18). |
| On-demand check routines | `Check` jobs (7.5). |
| Claude GitHub App acting as the owner; `add_repo`; `mcp__github__*` tools | Our own GitHub Apps, used only by the factory (section 6), and the session gateway's GitHub tools (5.8). |
| Repo `.mcp.json` / `.kiro/settings/mcp.json` entries that carry credentials (heroku-ops) | The session gateway's MCP proxy (5.8). |
| JDK25 cloud environment, `jdk25-setup.sh` | The sandbox image (section 8). |
| Run emails | The digest (5.16) and GitHub notifications on the bot's PRs. |
| "Create repo, grant access, open Claude Code" (manual) | `create_project`, then `start_session` (5.15). |

## 5. Design

### 5.1 Domain

The domain is closed ADTs and opaque ids (zen-of-james). A sketch:

```scala
opaque type SessionId = UUID
opaque type ProjectId = UUID            // a managed repo in the configuration
opaque type ModelId = String            // a value `kiro-cli chat --list-models` reports, e.g. "claude-opus-5.5"

enum JobKind derives CanEqual:
  case Maintenance(project: ProjectId)
  case Incident(project: ProjectId, incident: IncidentId)
  case Develop(project: ProjectId, task: OwnerText)       // interactive, driven by the owner (5.15)
  case IssueTriage(project: ProjectId, issue: IssueNumber)
  case Improve(proposal: ProposalId)                      // implements an approved proposal (5.12)
  case FactoryReview                                      // weekly review of findings and outcomes (5.12)
  case Sync                                               // in-process
  case OpsControl(source: OpsSourceId)                    // in-process (heroku-ops: drains and webhooks)
  case Check(check: CheckKind)

/** Who is calling the factory. Every MCP tool and endpoint declares which principals it accepts. */
enum Principal derives CanEqual:
  case Owner(user: AuthUserId)                            // signed in through zio-auth-server
  case Session(session: SessionId, project: ProjectId, kind: JobKindTag, grants: SessionGrants)
  case OpsSource(source: OpsSourceId)                     // a watcher, by its signing key

enum SessionState derives CanEqual:
  case Queued(at: Instant)
  case Starting(sandbox: SandboxId, at: Instant)
  case Running(sandbox: SandboxId, since: Instant, lastHeartbeat: Instant)
  case Suspended(sandbox: SandboxId, since: Instant)      // Develop only (5.15)
  case Parked(snapshot: SnapshotId, at: Instant)          // Develop only
  case Finished(outcome: Outcome, at: Instant)

enum Outcome derives CanEqual:
  case Succeeded(report: SessionReport)
  case Failed(reason: FailureReason)     // exit, time limit, sandbox lost, credit cap
  case Refused(reason: PolicyRefusal)    // budget, concurrency, policy: never started
```

A `SessionSpec` is a pure function of a job and the configuration. It contains:
- the prompt and its version;
- the model and effort;
- the project, and the `SessionGrants`: which gateway tools, which upstream MCP tools, and whether it may publish, open PRs, comment and request merges;
- the Skills the session gets beyond the project's own pins: `zen-of-factory` at the version the configuration pins, and any **overlays** (a Skill taken from a given commit for this session only, to try an unmerged change; 5.18);
- the tool policy for the runner;
- the size class;
- the time and credit limits.

### 5.2 Configuration (Postgres is the source of truth)

Postgres holds:
- **Projects:** repo, owner account, kind (library, server, site, …), schedule (day, time, time zone), model, size class, merge opt-ins, autonomy tier, notes. The notes replace FACTORY.md's per-project sections.
- **Ops sources and watched apps:** source kind, URL, keys (references to secrets), and which app maps to which project (5.5).
- **Upstream MCP servers per project** (5.8).
- **Prompts per job kind,** versioned.
- **Skill sources** (5.18): for each source repo, its provider, the path pattern inside it, whether the owner owns it (so the factory may propose changes to it), and how a change is released. The `zen-of-factory` pin lives here too.
- **Policy:** caps, the merge switch, the dispatch switch, incident policy per app, auto-apply classes for proposals (off by default).

Each change:
- goes through an owner MCP tool;
- is parsed into typed values first ("parse, don't validate"), so bad input is refused and never stored;
- creates a new configuration version that keeps the previous one, plus an audit row (who, when, before, after).

`config_history`, `config_diff(a, b)`, `config_revert(version)` and `config_export` (JSON) make every change visible and reversible. Every session records the configuration and prompt versions it ran with, and its Skill manifest (5.18), so outcomes can be compared across versions (5.12).

Backups:
- daily Heroku Postgres backups, on a plan whose retention covers at least a week;
- `Check(config-backup)`, which confirms the latest backup is less than 26 hours old.

The first import reads today's FACTORY.md tables once. FACTORY.md is then deleted, and what remains useful in it (owner setup steps for the GitHub Apps, the AWS account and Mailgun) moves into this repo's AGENTS.md.

### 5.3 Scheduler

- **Times:** each project's time zone (America/Denver today), computed with `java.time`, so DST needs no change.
- **Durability:** next-due times and claimed jobs are Postgres rows. A claim uses `SELECT … FOR UPDATE SKIP LOCKED` with an idempotency key (`project + due instant`), so Heroku's daily restarts neither lose nor double a run.
- **Missed runs:** a run missed by less than 6 hours runs late. Older ones are skipped and recorded.
- **Removed projects:** removing a project cancels its queued jobs.

### 5.4 Triggers

- **Schedule.**
- **GitHub events** arrive at the control App's webhook (`POST /github`, `X-Hub-Signature-256` checked, delivery ids de-duplicated):
  - `check_suite`/`check_run` completed: re-evaluate pending merge requests.
  - `installation_repositories`: report drift between the installations and the configuration.
  - `pull_request` closed: record the outcome of the factory's PRs. Merged, closed unmerged and reverted PRs are improvement signals (5.12).
  - Optional per project: `issues.opened` starts `IssueTriage` within the project's daily cap. The issue text is data (DESIGN 9).
- **Ops events** from ops sources (5.5).
- **Owner:** MCP tools only. Nothing a third party writes on GitHub starts privileged work.

### 5.5 Ops sources (pluggable)

An ops source watches production and reports. It doesn't decide what happens next. `jw-heroku-ops` is the first ops source, and others (another platform, an uptime monitor) implement the same protocol.

**Responsibilities:**

| | Ops source (heroku-ops) | Factory |
|---|---|---|
| Ingest | drains, platform webhooks, probes | — |
| Detect | classify lines, count in windows, apply thresholds (today's `Classifier`, `Detector` and alert rules) | — |
| Report | condition started, ongoing and cleared events, signed | verify, store, de-duplicate |
| Decide | — | the incident policy: record, notify, investigate, or (later) remediate |
| Rate limits and cooldowns | — | per app and per incident (today's 30-minute cooldown, 10 an hour, and 24-hour repeat move here) |
| Triage data | its read-only MCP tools | proxies them to sessions, pinned to the session's own app (5.8) |
| Fallback alarm | `/alarm` when the factory doesn't acknowledge an event | — |
| Control | — | `OpsControl` for that source (for heroku-ops: drains and webhooks through the allowlisted Heroku client) |

**Protocol v1** (JSON over HTTPS, in `core`):
- **Events:** the source sends `POST /ops/events` to the factory. Each body is signed with the source's key and carries a timestamp and nonce, so a replay is refused. Events:
  - `ConditionStarted`, `ConditionOngoing` and `ConditionCleared`: app, condition (crash, timeout, router error, server error, memory, app error, probe down, release failed, build failed), counts, window, detected-at, and up to N samples, each whitelisted, truncated and quoted;
  - `Deploy`: app, release, status;
  - `Heartbeat`.

  The factory answers `202` with an event id once it has stored the event. An event that isn't acknowledged is retried, and after 30 minutes without acknowledgement the source raises `/alarm`, as heroku-ops does today for undelivered incidents.
- **Configuration:** the source pulls `GET /ops/config` (signed) every 5 minutes, which returns its watched apps and their thresholds. It keeps the last good copy if a pull fails.
- **Triage:** the source serves its read-only MCP server (heroku-ops' `OpsMcp`, unchanged). Only the factory calls it, with a credential the factory holds.

**The factory's incident policy** is a pure, unit-tested function of the event, the open incidents, the app's policy and the caps:
- **Correlate:** an open incident per app and condition is created by `ConditionStarted` and closed by `ConditionCleared`. A deploy within the window is attached.
- **Decide:** one of `Record` (store only), `Notify` (`needs_you` and the digest), `Investigate` (start an `Incident` session on the project, at most one open session per incident) or later `Remediate` (autonomy tiers, DESIGN 11). Each app's configuration says which decisions are allowed. New apps start at `Investigate`, so no automatic fixes.
- **Learn:** the owner can mark an incident as a false positive or as expected (`mark_incident`). These marks become proposals to change the source's thresholds (5.12).

`Incident` sessions do what zen-of-factory's incident procedure says: gather evidence through the proxied triage tools, open or update the incident issue, and open a fix PR only when the fix is clear and small, which the owner merges.

heroku-ops' changes:
- `Fire` and the `FIRE_TOKEN_*` config vars are replaced by a `FactoryReporter`.
- Watched apps come from `/ops/config` instead of FACTORY.md.
- Cooldowns and rate limits move to the factory.
- The detection, the MCP server and the alarm stay as they are.

### 5.6 Sandboxes (pluggable)

A sandbox is where one session runs. The factory sees a substrate only through this interface:

```scala
trait Sandboxes:
  def start(spec: SandboxSpec): IO[SandboxError, SandboxId]    // size class, time limit, bootstrap token
  def status(id: SandboxId): IO[SandboxError, SandboxStatus]    // Starting | Running | Suspended | Gone(reason)
  def suspend(id: SandboxId): IO[SandboxError, Unit]            // substrates that can keep memory and disk
  def resume(id: SandboxId): IO[SandboxError, Unit]
  def stop(id: SandboxId): IO[SandboxError, Unit]
  def capabilities: SubstrateCapabilities                       // docker, max duration, sizes, suspend
```

The rest of the protocol is substrate-neutral:
- The runner connects out to the factory over HTTPS. The factory never connects in, so sandboxes need no ingress.
- The only input a substrate must deliver is a single-use bootstrap token and the factory URL.
- A `SessionSpec` names a size class (`small`, `medium`, `large`), which each substrate maps to its own sizes.
- A spec needing a capability (Docker, more than 8 hours) is only placed on a substrate that has it.

#### The first substrate: AWS Lambda MicroVMs

Lambda MicroVMs launched on 2026-06-22. Each one is a Firecracker VM for one tenant or session, started from a snapshot of a prebuilt image. It runs for up to 8 hours, can be suspended and resumed with memory and disk kept, and is billed per second.

- **Image:**
  - A ZIP holding a `Dockerfile` and the runner, in S3. Lambda builds it on its Amazon Linux 2023 base, starts it, and snapshots memory and disk. Each update is a new image version.
  - Built for x86_64 (`--cpu-configurations architecture=X86_64`), which needs base image version 3 or later.
- **Capabilities:** the image sets `additionalOsCapabilities: ["ALL"]`, which AWS says are "applied within the VM isolation boundary". Docker needs them, and per one early user's notes, "Docker absolutely works" in a MicroVM (D3). The runner uses them to set up users, rootless Docker and `ptrace_scope` (7.2), then drops them for everything it starts.
- **Start:** `run-microvm` with:
  - `--run-hook-payload` (at most 16 KB, delivered to the `/run` hook), carrying the bootstrap token;
  - `--maximum-duration-in-seconds`, at most 28,800;
  - `--ingress-network-connectors NO_INGRESS` and the Lambda-managed `INTERNET_EGRESS` connector: public egress with no VPC and no NAT gateway (8.4);
  - no `--execution-role-arn`, so the VM has no AWS credentials.
- **Lifecycle hooks:** `/run`, `/suspend`, `/resume` and `/terminate`, served by the runner on port 9000 (an early user reports hooks arrive only there).
- **Sizes:**

  | Class | Baseline | Peak (automatic, up to 4×) | Disk | Use |
  |---|---|---|---|---|
  | `small` | 2 GB / 1 vCPU | 8 GB / 4 vCPU | 8 GB | Node repos, sites, docs, triage, reviews |
  | `medium` (default) | 4 GB / 2 vCPU | 16 GB / 8 vCPU | 16 GB | JVM builds with Docker and Testcontainers |
  | `large` | 8 GB / 4 vCPU | 32 GB / 16 vCPU | 32 GB | big builds |
- **Limits:**
  - 8 hours per MicroVM, including suspended time.
  - An account memory quota of 400 GB (1,024 GB in the larger regions; new accounts start lower).
  - `RunMicrovm` and `ResumeMicrovm` at 5 per second.
  - Outbound UDP is blocked, so Docker containers need the Lambda DNS address.
  - About 7.2 GB of free disk at image build.
- **Credentials:**
  - The factory calls the API with an IAM user in a dedicated AWS account, allowed only to run, get, list, suspend, resume and terminate MicroVMs of the one image. It can't open a shell into a session.
  - Image builds run from this repo's CI through GitHub OIDC.

#### Substrates considered and rejected for now

| Substrate | Why not |
|---|---|
| **Heroku one-off dynos** | No Docker: measured (M1). |
| **AWS Lambda Managed Instances** | Containers on EC2 that AWS says "do not provide security isolation between workloads"; at least 3 instances by default and no scale to zero; 90 minutes at most; Docker not documented. |
| **AWS CodeBuild** | A build-job model with no suspend and resume for `Develop`. |
| **GitHub Actions** | Queue latency, 6 hours, and the bootstrap token passes as a workflow input. |
| **A VM we operate** (EC2 per session) | Docker works, but we build the isolation and pay for idle time. The next candidate if E6 fails. |

### 5.7 Inside a session: the runner

The runner is a ZIO program in the image, the ACP client of kiro-cli, built on `zio-acp`. We ran each kiro-cli behavior below locally with kiro-cli 3.1.0 (section 14).

1. **Bootstrap.**
   - In the `/run` hook the runner reads the bootstrap token from the payload and exchanges it at `POST /session/open` for the **session token** (5.8), which it keeps in memory only. The bootstrap token is dead after that.
   - It sets up the users and rootless Docker (7.2).
   - The response holds the `SessionSpec`.
2. **Checkout.**
   - The runner asks the gateway for a checkout (`POST /session/checkout`). The answer is a read-only installation token (Contents: read, the one repository, 1 hour), plus the refs to fetch: the default branch and the session's existing `factory/*` branch, if one is open.
   - The runner, as root, clones with it into the workspace, rewrites the remote to the token-free URL, and drops the token. The agent never sees a GitHub token.
   - Public repos need no token at all.
3. **Restore caches** for the project (8.5), then **install Skills** (5.18):
   - each of the project's pins, through its provider (the repo's build for SkillsJars, `skills install --frozen-lockfile` for skills.sh, the skillsjars CLI for the rest);
   - `zen-of-factory` and any overlays from the `SessionSpec`, which replace a pinned Skill of the same name;
   - then the hash of every installed Skill is computed and sent as the session's **Skill manifest** (`POST /session/skills`). A Skill whose content doesn't match its pin's hash stops the session before the agent starts.
4. **Agent.**
   - The runner starts `kiro-cli acp --agent-engine v3 --auth-method client --agent factory` over stdio (`AcpClient.launch`), as the `kiro` user.
   - The model goes in `session/new` as `_meta.kiro.modelId`, because `--model` isn't accepted with v3.
   - It advertises the client capabilities `terminal` and `fs`.
   - It sends `session/new` with the workspace and one MCP server, `factory` (stdio: `runner mcp`, 5.8), then `session/prompt` with the job's prompt.
5. **Kiro access tokens.** kiro-cli asks with `_kiro/auth/getAccessToken`. The runner gets `{accessToken, expiresAt, profileArn}` from the gateway (`POST /session/kiro-token`) and passes it on without logging it (experiment E1 settles what the token is).
6. **Tool calls.**
   - Shell commands arrive as `terminal/create`. The runner runs each one as the `agent` user with a clean environment (toolchain variables, `DOCKER_HOST` for that user's rootless daemon). Never a secret.
   - Every tool call also arrives as `session/request_permission`. The runner answers by policy, a pure function of the `SessionSpec`. It allows work inside the workspace, and denies writes outside the workspace and `/tmp`, any `git push` (publishing goes through `publish`, 5.8), and anything else the job kind forbids.
   - Every decision is recorded.
7. **Transcript and usage.**
   - Every `session/update` and `_kiro/*` notification goes to `POST /session/events`.
   - Credits come from Kiro's `turn_completion` and `session_usage` notifications. The runner cancels the session at its cap.
8. **Finish.**
   - The runner saves the project's caches if they changed and the session's rules allow it (8.5).
   - It calls `POST /session/finish` with the stop reason, duration, credits and `kiro-cli --version`, and exits.
   - The factory revokes the session token and terminates the MicroVM.

The factory agent configuration (`~kiro/.kiro/agents/factory.json`, written by the runner, in user scope):
- `resources`: steering, Skills and AGENTS.md;
- `includeMcpJson: true`, for the repo's own MCP servers that need no credentials (stdio servers like `./sbta --mcp`, public ones like javadocs.dev);
- the `factory` MCP server.

Kiro always denies writes to `.kiro/settings/`, so a session can't change its own MCP configuration.

### 5.8 The session gateway: identity, GitHub, and the MCP proxy

Every session talks back to the factory through one MCP connection. kiro-cli sees it as a stdio server, `runner mcp`, which the runner bridges to `POST /session/mcp` on the factory, adding the session token. No bearer token appears in any MCP configuration, and the `kiro` and `agent` users can't read the token (7.2).

#### Session identity

- **The session token** is 256 random bits, stored hashed, issued at `/session/open`, and revoked at finish or at the session's time limit. It resolves to `Principal.Session(session, project, kind, grants)`.
- **Scoped to one project.** Every tool on `/session/mcp` takes the project from the principal, never from its arguments. A session can't name another repo, another app or another session.
- **Grants come from the job kind and the project's configuration.** A `Maintenance` session may publish, open PRs and request merges. An `Incident` session may publish and open PRs but not request merges. A `FactoryReview` session may only read, and propose.
- **Separate from the owner's MCP.** `/session/mcp` accepts only session tokens, and `/mcp` only the owner's OAuth tokens. They are separate zio-http-mcp servers with separate `TokenVerifier`s, so neither kind of token works on the other server.

#### Does a session need GitHub access? No. The factory does GitHub for it.

Today's routines push, open PRs, comment and merge with their own GitHub access. Here the session gets none of that:

| Need | How |
|---|---|
| Read the repo | Checkout by the runner with a read-only token the agent never sees (5.7). |
| Push work | `publish(branch, message)`: a runner-local tool. The runner computes the change set from the workspace against the checkout base (changed files with contents, deletions, modes) and sends it to the gateway. The gateway checks it, then creates the commit with GitHub's Git Data API as `jw-factory-agent` and updates `factory/<kind>-<name>`. Commits made through the API this way are signed by GitHub. |
| PRs, issues, comments, labels | `open_or_update_pr(title, body, labels)`, `comment(target, body)`, `issues(filter)`, `issue(number)`, `pr(number)`, all on the session's own repo. |
| CI | `ci_status(ref)` and `ci_logs(run, job)`, with log text returned quoted and truncated. |
| Merges | `request_merge(pr)` and `merge_status(pr)`, decided by the merge policy (5.10). |
| Dependabot alerts | `dependabot_alerts()`, read with the control App. Routines couldn't read these (DESIGN 1, "403"). |

Why this design:
- **No GitHub credential in the sandbox** that can write anything, so nothing for a compromised session to steal or misuse.
- **Every write is checked before it happens,** rather than relying on GitHub to reject it afterwards. `publish` refuses:
  - branches outside `factory/**`;
  - changes to `.github/` beyond action version lines (unless the job kind allows more);
  - `.kiro/settings/` and other factory-owned paths not in the job's class;
  - files over a size cap, change sets over a file-count cap, and content matching secret patterns.
- **Text the session reads is marked by who wrote it.** Issue and PR tools return `{author, isOwner, text}` with the text quoted, so DESIGN 9 is in the data, not just the prompt.
- **One audit trail:** every push, PR, comment and merge request has a session, a principal and a policy decision.
- **The agent App's tokens never leave the factory,** and the rulesets (6.3) still stop that identity from touching default branches if the gateway has a bug.

The cost: the Skills' GitHub steps change from `gh` and `mcp__github__*` to these tools (section 11), and anything they don't cover needs a new tool. `git log`, `git diff` and local branches work as usual in the workspace. Fetching more history or another ref is a runner-local `fetch(ref)` tool, which the runner does with a fresh read-only token.

#### The MCP proxy: upstream servers with credentials

Some MCP servers a project uses need credentials: heroku-ops' triage tools today, and later others with OAuth (an issue tracker, a cloud provider). Those credentials must not enter the sandbox. So the gateway proxies them:
- **Configured per project:** `UpstreamMcp(name, url, auth, toolAllowlist, pinnedArguments)`. `auth` is one of:
  - `None`;
  - `Bearer(secretRef)`;
  - `OAuthConnection(connectionId)`: the owner connects it once through MCP, as with `connect_github`;
  - `OpsSource(sourceId)`: the factory's own credential for that ops source.
- **At session start** the gateway lists the upstream's tools (zio-http-mcp's `McpClient`, which supports static headers and OAuth authorization code with PKCE), keeps the allowlisted ones, and exposes them as `<name>__<tool>` on the session's MCP.
- **Pinned arguments** fix what a session may ask about. For heroku-ops, `app` is pinned to the project's watched app, so an `Incident` session for javadocs can't read json-paste's events.
- **Results are returned quoted,** like every other tool's, and every call is audited.

Servers that need no credentials can stay direct in the repo's `.kiro/settings/mcp.json`: stdio servers like `./sbta --mcp`, which must run in the workspace anyway, and public ones like javadocs.dev. `Sync` stops seeding the heroku-ops entry, and the proxy replaces it.

#### Gateway endpoints

| Endpoint | Does |
|---|---|
| `POST /session/open` | Bootstrap token (single use, 10 minutes) for the session token and the `SessionSpec`. |
| `POST /session/checkout` | Read-only installation token and refs, for the runner. |
| `POST /session/kiro-token` | Kiro access token. Refused after the credit cap. |
| `POST /session/publish` | A change set, checked and committed (above). |
| `POST /session/cache/restore`, `/session/cache/save` | Presigned, single-object S3 URLs for the project's caches (8.5). |
| `POST /session/events`, `/heartbeat`, `/finish` | Transcript, liveness, end. |
| `POST /session/skills` | The session's Skill manifest (5.18). |
| `POST /session/snapshot` | Develop only: the Kiro session store and uncommitted changes (5.15). |
| MCP `POST /session/mcp` | The session's tools: GitHub tools, `request_merge`, `report_finding`, `spec`, and the proxied upstream tools. |

### 5.9 Prompts

Prompts are configuration (5.2), versioned per job kind. What a factory session needs to know (the gateway tools, `report_finding`, the routine class, the incident procedure) is in the `zen-of-factory` Skill (5.18), so a prompt stays a short pointer. The maintenance prompt:

> Follow `.factory/MAINTENANCE.md` in this repository and the `zen-of-factory` Skill. Publish your work with the `factory` MCP server's `publish` and `open_or_update_pr` tools, and request merges with `request_merge`; the factory decides. Report anything about the factory, a Skill or this repository's instructions that should change with `report_finding`.

### 5.10 Merge policy

`request_merge(pr)` records a request. The control App merges only when every check passes. The checks are pure functions of facts from GitHub, unit-tested, with no model involved:
1. **The session's grants allow merges.** `Incident`, `IssueTriage`, `Develop`, `Improve` and control-plane repos never do.
2. **The PR is the session's own:** in the project's repo, from the session's `factory/*` branch, authored by the agent App.
3. **CI is green.** Every check run on the head commit succeeded. Pending checks wait, re-evaluated on `check_suite` events for up to 2 hours.
4. **Every changed path is in the routine class** (DESIGN 4 and 11 as code):
   - build and dependency declarations and lockfiles;
   - action version lines in `.github/workflows/*`;
   - factory-owned files;
   - for `Maintenance`, small fixes for a bump (at most N lines, default 50), outside the paths DESIGN 11 always holds: `Procfile`, `system.properties`, `.github/` beyond version lines, migrations, and auth and security code named in AGENTS.md.
5. **No major bump** of a direct dependency without an opt-in in the configuration. A diff parser decides.
6. **No hold:** no `merge-hold` label, and the merge switch is on.

If a check fails, the factory adds `needs-human`, comments with the failed check, and the session sees the result through `merge_status`.

### 5.11 In-process jobs

These are our code, run in the factory process, never in a sandbox:
- **`Sync`, daily.** It is the ZIO port of `factory-sync.py` and `factory_sbta.py`:
  - It works through GitHub's APIs, with no clones.
  - It opens or updates `Factory sync:` PRs, which merge through the merge policy (factory-owned paths only).
  - It creates labels, files and closes `ci-red` issues, and checks repository settings (read-only).
  - Its pure parts are unit-tested functions: which files differ, how sbta launchers are rewritten, MCP entry merging, line endings.
- **`OpsControl` per source:** for heroku-ops, today's `factory-heroku.py` allowlist (GET apps, drains, webhooks, features; POST and DELETE drains and webhooks), as typed operations.
- **`Digest`, daily** (5.16).
- **Metrics,** hourly (5.12).
- **Housekeeping:** terminate MicroVMs with no live session, expire snapshots and old events, and check the backup's age.

### 5.12 Self-improvement

Today the factory improves itself through PRs: each run's step 8 opens `Factory improvement:` PRs to `jamesward/skills` and the factory repo, and the owner reviews them. Now the factory's behavior lives in several places, and each improves differently:

| What changes | Where it lives | How a change lands |
|---|---|---|
| Prompts, policy, caps, schedules, ops thresholds | Postgres configuration | a new configuration version, applied by the factory once the owner approves |
| An owned Skill (`jamesward/skills`: zen-of-projects, zen-of-scala, zen-of-factory, …) | its source repo, found through the session's Skill manifest (5.18) | a PR from an `Improve` session, tried with overlays, merged by the owner, then rolled out (5.18) |
| A Skill the owner doesn't own | its maintainer's repo | a drafted issue for the maintainer, or a note in the project's `AGENTS.md`; never a PR |
| The factory's code (`jw-factory`, the runner, `ops`) | this repo | a PR from an `Improve` session, merged by the owner (control plane: never merged automatically) |
| A project's own instructions (AGENTS.md) | the project's repo | a PR from that project's next session, which gets the finding as input. AGENTS.md isn't in the routine class, so the owner merges it. |

The loop:

1. **Findings come from sessions.** `report_finding(target, kind, summary, evidence, suggestion)`:
   - `target` is one of `skill(name)`, `prompt`, `policy`, `factory`, `ops`, `project`;
   - `kind` is one of `friction`, `wrong-instruction`, `missing-instruction`, `guess`, `tool-bug`, `noise`;
   - `evidence` is quoted from the session.

   This replaces step 8's PRs. zen-of-factory says what to report, and the tool records it. A `skill(name)` finding is resolved through the session's Skill manifest to the exact source, path and commit the session read. If that passage has changed on the source's default branch since, the finding is marked "may already be fixed" with the newer commit, so a project on an old pin doesn't propose a change that's already made.
2. **Outcomes come from the factory's own data,** which routines never had:
   - per session: success, failure, refusal, duration, credits, retries, tool denials, merge-policy refusals by rule, CI failures;
   - per PR: merged, closed unmerged, reverted, time to merge;
   - per incident: time to detect, time to issue, the owner's false-positive marks;
   - all tagged with the configuration and prompt versions and the Skill manifest the session ran with.
3. **Clustering.** The factory groups findings by target, kind and a normalized summary. The same finding from 2 or more projects is promoted from `project` to `skill` or `factory` (DESIGN 6: the same problem in several repos is a factory problem). A finding that conflicts with an exception in the project's `AGENTS.md` stays a `project` finding: the exception, with its reason, wins over the general Skill.
4. **The weekly `FactoryReview` session** (`small`, read-only grants):
   - It reads the clusters, the outcome metrics, rejected proposals and their reasons, and transcripts on demand (`transcript_search`), all as quoted data.
   - It writes proposals with `propose(target, change, rationale, evidence, expectedEffect)`. For configuration targets, `change` is a typed configuration diff. For code and Skill targets, it is a task description for an `Improve` session.
   - It can't apply anything.
5. **The owner decides** through MCP: `proposals`, `approve_proposal(id)`, `reject_proposal(id, reason)`.
   - Approving a configuration proposal creates the new configuration version.
   - Approving a code or Skill proposal starts an `Improve` session on that repo, which opens a PR the owner merges. For a Skill, the owner can first try the PR's commit as an overlay on chosen projects' next sessions (5.18).
   - Rejection reasons are kept and shown to later reviews, so the same idea isn't proposed again without new evidence.
6. **The factory checks the effect.** After a configuration, prompt or Skill change has run for a set number of sessions, the next review compares the outcomes before and after, using the version tags and Skill manifests, and says whether it helped. A change that made things worse becomes a revert proposal. This is the ground for today's planned hill climbing.

**Autonomy grows only by the owner's choice** (DESIGN 4). A class of configuration proposal (for example "raise a cap within its maximum" or "lower an ops threshold after N false positives") can be set to auto-apply, off by default. Each auto-applied change is still a configuration version, audited, revertable, and listed in the digest.

### 5.13 The owner's MCP

The MCP server is `POST /mcp` on `jw-factory` (zio-http-mcp, stateless Streamable HTTP, an OAuth 2.1 resource server). It accepts only `Principal.Owner`.

| Group | Tools |
|---|---|
| Status | `status`, `needs_you`, `schedule`, `metrics(filter)` |
| Sessions | `start_session(kind, project, task?)`, `send_message(session, text)`, `session(id)`, `session_events(id, since)`, `transcript(id, query?)`, `cancel_session(id)`, `sessions(filter)` |
| Projects | `create_project(…)`, `onboard_repo(repo)`, `check_repo(repo)`, `apply_repo_settings(repo)` |
| Connections | `connect_github`, `disconnect_github`, `connect_upstream(name)`, `disconnect_upstream(name)` |
| Ops | `incidents(filter)`, `incident(id)`, `mark_incident(id, falsePositive \| expected \| real)`, `ops_sources` |
| Improvement | `findings(filter)`, `proposals(filter)`, `approve_proposal(id)`, `reject_proposal(id, reason)`, `try_skill(source, commit, projects)` (overlays, 5.18) |
| Skills | `skill_sources`, `set_skill_source`, `skills(project)` (pins and what recent sessions ran), `skill_usage(skill)` (which projects and sessions use which commit) |
| Configuration | `config_get`, `config_set`, `add_project`, `remove_project`, `set_schedule`, `set_model`, `set_prompt`, `set_upstream`, `set_incident_policy`, `config_history`, `config_diff`, `config_revert`, `config_export` |
| Control | `set_merge_switch`, `set_dispatch_switch`, `run_now(job)`, `merge_requests`, `identities`, `unlink_identity`, `sign_out` |

Every write is an audit row. Transcripts, findings and session events are returned as quoted data.

### 5.14 `zio-auth-server`, with pluggable persistence

claude.ai's custom connectors need OAuth, and zio-http-mcp is a resource server only. The authorization server is `zio-auth-server` (`jamesward/zio-auth-server`, `com.jamesward::zio-auth-server`), extracted from toolbook's `login` module. **Built and released:** 0.0.1 and 0.0.2 (2026-11-13), and toolbook's production `login` runs on it.

What it ships:
- authorization code with PKCE (S256 only, single-use codes, exact `redirect_uri`, loopback redirects per RFC 8252);
- refresh tokens that rotate, with a replay revoking the whole chain;
- RFC 8414 metadata, RFC 7591 Dynamic Client Registration (the path claude.ai uses), Client ID Metadata Documents, RFC 8707 resource indicators, RFC 7662 introspection and RFC 7009 revocation;
- RS256 access JWTs, with `/oauth/jwks`, and an `Mcp` client profile;
- email sign-in (magic link and one-time code, Mailgun), browser sessions with a CSRF cookie and an access-JWT cookie, `/login?return_to=` resuming a live session;
- passkeys (Yubico's `webauthn-server-core`), step-up, email change, a `SignUpPolicy` (`Open`, `ExistingUsersOnly`, `Allowlist`), the `OAuthConnector` for upstream connections (GitHub included, tokens sealed with AES-GCM), and an `AccessTokenVerifier` for a resource server sharing the store;
- overridable `Pages[U]` and `Messages`, and an `AuthorizeGate` (toolbook's terms and purchase checks).

Security fixed before and in the first releases (a review on 2026-11-13):
- **Consent:** a signed-in user approves each client on a consent page before it gets a code, except clients in `AuthServerConfig.skipConsent`. Before, a link to `/oauth/authorize` gave any registered client a code for a signed-in user.
- **Scopes:** a token carries the user's own scopes (`AccountView.scopes`) plus only the requested scopes in `clientScopes`. A dynamically registered client can't ask its way to `role:admin`. Production had no escalated tokens or clients (checked on `mytoolbook-api`).
- **Refresh re-reads the user** (`AuthStore.findActiveUser`): a deleted or disabled user's refresh fails and revokes the chain, and the new token has the user's current scopes.
- **One-time codes:** at most 5 per address per hour, so the 5 guesses per code can't be repeated without limit.
- **Introspection** reports a token active only to its own client, or to one in `introspectAnyToken`.
- **CIMD** documents are fetched only over HTTPS from public addresses (`CimdAddressPolicy`), never loopback, private or link-local ones such as cloud metadata. A DNS answer that changes between the check and the fetch isn't caught.
- **Connection callbacks** require a session of the user who started the connection.

**Modules:**

| Artifact | Contains |
|---|---|
| `zio-auth-server` | The server, the store interfaces, passkeys, step-up, connections, Mailgun. |
| `zio-auth-server-magnum` | Every store on Postgres with zio-magnum, in its own schema (`auth`, user type `AuthUser`), with its own Flyway run and history (`AuthSchema.migrate`). **The factory uses this one.** |
| `zio-auth-server-test` | Test scope only: `InMemoryAuthStore`, `AuthStoreContract` (one suite per store interface), a software passkey authenticator, a recording mailer, dev cookies, fixed sessions, throwaway keys. |
| (in toolbook) `ApiAuthStore` | Toolbook's `ApiClient` adapted to `AuthStore[User]`, so its database stays behind its API. The api runs the contract against its handlers and Postgres (`ApiAuthStoreContractSpec`); its concurrent tests found the api's code exchange as a read and a separate update, now one locked transaction. |

**Store interfaces,** generic over the application's user type through `AccountView[U]` (id, email, scopes):

```scala
trait AuthStore[U]:      // sign-in, sessions, clients, grants, access and refresh tokens; consumeGrant and rotateRefreshToken atomic
trait AccountStore[U]:   // findUser, startSession (passkeys), email change
trait PasskeyStore:      // credentials and ceremonies
trait ConnectionStore:   // upstream connections and their states
```

A new need goes into a new trait, or an `AuthStore` method with a default. When no default is safe (a default for `findActiveUser` would skip the check), the method is added and toolbook's `ApiAuthStore` and api change with the release.

**In the factory:**
- One database: the factory's schema plus `auth`, which `AuthSchema.migrate` runs first, separately from the factory's own migrations.
- `/mcp` verifies owner tokens in-process (`AccessTokenVerifier`): no introspection call and no introspection credentials. It accepts a token only if its user is the configured owner and its resource is the factory's MCP URL.
- **Sign-in:** email (Mailgun) or a passkey (the owner registers at least two), with `SignUpPolicy.ExistingUsersOnly`. Both are linked to one user, so losing one doesn't lock the owner out.
- **Step-up:** adding a passkey or changing the email address needs a session that started in the last 10 minutes. Signing in with an unknown identity never creates or joins an account.
- **Consent:** claude.ai and local MCP clients register dynamically, so the owner approves each on the consent page.
- **GitHub is a connection, not a sign-in.**
  - `connect_github` runs the `jw-factory-owner` App's OAuth flow through `OAuthConnector` and stores the owner's GitHub user and refresh tokens, sealed, in the `ConnectionStore`. The callback checks that the GitHub user id is the configured owner's.
  - Only `create_project` and `apply_repo_settings` use it. When it lapses, those two ask the owner to reconnect.
  - `disconnect_github` revokes it.
- **Outages:** existing 365-day tokens don't depend on Mailgun.
- **Emergency switches outside the factory:** `FACTORY_DISPATCH=off` as a Heroku config var, and terminating MicroVMs in the AWS console.

### 5.15 Projects and interactive sessions

This replaces "create a repo, grant the Claude App access, open Claude Code".

1. **`create_project(owner, name, description, kind, visibility)`:**
   - creates the repo with the owner's GitHub connection;
   - adds it to the `jw-factory` and `jw-factory-agent` installations (`PUT /user/installations/{id}/repositories/{repo_id}`, E4);
   - applies the rulesets and settings;
   - adds the project to the configuration, and seeds the bootstrap files through `Sync`.
2. **`start_session(Develop, project, task)`:** the owner, usually in claude.ai, follows with `session_events` and steers with `send_message`, which becomes the next `session/prompt`. The session publishes with `publish` and opens PRs, and the owner merges them.
3. **Between messages:**
   - After an idle time (default 10 minutes) the factory suspends the MicroVM, keeping memory and disk, and resumes it on the next message.
   - Before the VM's 8 hours run out, the runner parks the session: it publishes work in progress to `factory/develop-<id>` and uploads the Kiro session store. A later message starts a new MicroVM, which restores the store and calls `session/load`.

### 5.16 Notifications

The MCP server is the only way *in*. The factory still writes *out*:
- **GitHub activity:** sessions act as the agent bot, so GitHub notifies the owner of the bot's PRs and issues.
- **The digest:** daily, the factory comments on a pinned `Factory digest` issue in this repo (GitHub emails it). It covers `needs_you`, open proposals, incidents, refused runs, budgets used, auto-applied changes and sign-in or identity changes. `needs_you` returns the same list.
- **Findings about the factory's own health** (repeated failures, exhausted budgets, a stale backup, an ops source silent past its heartbeat) are `needs-human` items.
- **The ops source's `/alarm`** stays the fallback when the factory doesn't acknowledge events.

### 5.17 Persistence

There is one Heroku Postgres database:
- **Schema `factory`:**
  - `config_version`, `audit`, `schedule_state`;
  - `job` and `session`, with state transitions, MicroVM id, model, credits, duration, size and configuration version;
  - `session_event` (compressed, kept 90 days) and `snapshot`;
  - `publish`, `merge_request`, `github_delivery`;
  - `ops_event`, `incident`;
  - `finding`, `finding_cluster`, `proposal`, `metric`;
  - `skill_source`, `session_skill` (the Skill manifests), `skill_overlay`;
  - `upstream_mcp`.
- **Schema `auth`:** `zio-auth-server-magnum`'s users, sessions, challenges, clients, grants, tokens, passkeys and connections (including the sealed GitHub connection), migrated by its own Flyway run with its history in `auth.flyway_schema_history`.

Migrations run at startup from versioned SQL: `AuthSchema.migrate` first, then the factory's.

### 5.18 Skills

Skills carry the guidance every project shares: zen-of-projects, zen-of-scala, zen-of-james, zen-of-websites and, for factory sessions, zen-of-factory. Each project chooses which it uses, and they work the same outside the factory (the owner's own sessions in Kiro or Claude Code). For self-improvement the factory has to know, for every session, exactly which Skill text it ran with and where that text lives, so a finding can turn into a change in the right repo.

**Both providers already pin a Skill to a git commit:**
- **SkillsJars:** skillsjars.com deploys a GitHub repo's Skills to Maven Central, one jar per Skill, versioned by the commit (`yyyy_MM_dd-<sha7>`, for example `anthropics__skills__pdf:2026_02_25-3d59511`), with the repo in the POM's `scm`. The owner's `com.jamesward:skills` is instead released by hand with the Maven release plugin (`skills-0.0.27`, its tag in the POM), which is where today's release churn comes from. A project declares Skills in its build (`% Skills` in sbt, `skillsjars:extract` in Maven, `skill(...)` in Gradle) and extracts them to `.kiro/skills/`.
- **skills.sh** ([vercel-labs/skills](https://github.com/vercel-labs/skills)): `npx skills add owner/repo` installs from GitHub directly, with no package registry. `skills-lock.json` (version 2) records each Skill's `source`, `skillPath`, `commit` and a `computedHash` whose algorithm the README specifies with test vectors. `skills install --frozen-lockfile` restores exactly that commit and checks the hash, and `skills update` moves the commit forward.

**The factory's model is provider-neutral:**

```scala
final case class SkillRef(
    provider: SkillProviderId,  // SkillsJars | SkillsSh | …
    name:     SkillName,
    source:   RepoRef,          // GitHub owner/repo
    path:     String,           // the Skill's folder in the repo
    commit:   CommitSha,
    hash:     ContentHash,      // skills.sh's computedHash algorithm, for every provider
)

trait SkillProvider:
  def declared(repo: Workspace): IO[SkillError, List[SkillRef]]           // the project's pins
  def install(refs: List[SkillRef], into: Path): IO[SkillError, Unit]     // in the runner
  def bump(repo: Workspace, ref: SkillRef, to: CommitSha): IO[SkillError, ChangeSet]
  def overlay(ref: SkillRef, from: CommitSha): SkillRef                   // one session only
```

- `declared` reads the build (SkillsJars: the commit from the version, the repo from the POM's `scm`; `com.jamesward:skills`' release tags resolve the same way), `skills-lock.json`, or the skillsjars CLI's lock.
- `bump` is the edit `Sync` or a maintenance session makes to move a pin. A pin bump is a dependency change, so it is in the routine class and merges on green CI (5.10).
- One `SkillHash` implementation, tested against skills.sh's test vectors, checks every provider's installed files.

**Pinning without a JVM build:** a **skillsjars CLI** (to be built) pins and installs Skills for node repos, static sites and anything else. Its lock file uses skills.sh's `skills-lock.json` format, with a `sourceType` for SkillsJars coordinates, so a repo has one lock format and both tools can restore it. It replaces the sites' clone of `jamesward/skills` main, so every project's guidance is pinned and reproducible.

**Skill sources** are configuration (5.2), keyed by provider and repo: the path pattern, whether the owner owns it, and how a change is released. Only owned sources get `Improve` PRs. The POM's `scm` and the lock's `source` fill a new source in automatically, as not owned until the owner says otherwise.

**The Skill manifest:** at session start the runner installs the pins, then the `SessionSpec`'s additions, and sends `POST /session/skills` with the `SkillRef` and the computed hash of each installed Skill (5.7). It is stored on the session row next to the configuration and prompt versions, so:
- `report_finding(skill(name))` resolves to the exact source, path and commit (5.12);
- the review compares outcomes by Skill commit;
- `skill_usage` shows which projects run which commit.

**zen-of-factory** is the factory's own guidance as a Skill, in `jamesward/skills`: the gateway tools instead of `git push` and `gh`, `report_finding`, the routine class, the incident procedure, and how to read quoted text. The factory adds it to every factory session at the commit its configuration pins, whatever the project declares. Outside the factory it never loads. It improves like any owned Skill. DESIGN.md stays with the factory's code, and only the owner changes it.

**Which instruction wins:** the project's `AGENTS.md` (exceptions, each with its reason), then zen-of-factory (factory sessions only), then the general Skills. A finding that conflicts with a project exception is a `project` finding (5.12).

**Changing an owned Skill:**
1. **Propose:** an approved proposal starts an `Improve` session on the Skill's repo, which opens a PR.
2. **Try:** `try_skill(source, commit, projects)` sets an overlay (the Skill from the PR's commit) on those projects' next sessions. The sessions are tagged with it, so the review can compare them with sessions on the pinned commit. Overlays replace the hand-copied `.factory/skills/` files.
3. **Merge:** the owner merges. Skill text never merges automatically (DESIGN 4).
4. **Release and roll out:** open (section 15). The factory's model needs only a commit, so a release is whatever the provider needs to serve that commit: nothing for skills.sh, a skillsjars.com deploy for a SkillsJars jar, a tag for `com.jamesward:skills`.
5. **Measure:** step 6 of 5.12, by Skill commit.

**Skills the owner doesn't own** (for example `anthropics/skills` through SkillsJars, or a skills.sh Skill) are installed and recorded the same way. A finding about one becomes a drafted issue for its maintainer, which the owner files, or a note in the project's `AGENTS.md`.

## 6. GitHub access

### 6.1 Three GitHub Apps

All three are installed on the 5 owners with "Only select repositories". **No GitHub token ever reaches a sandbox** except the read-only checkout token, which only the runner holds, briefly.

| App | Used by | Permissions | Private key |
|---|---|---|---|
| `jw-factory-agent` | the session gateway: commits, branches, PRs, comments, labels for sessions; read-only checkout tokens | Contents: write, Pull requests: write, Issues: write, Workflows: write (for action version bumps, after `publish`'s path check), Checks: read, Commit statuses: read, Metadata: read | in `jw-factory` |
| `jw-factory` | the control plane: merges, `Sync`, `ci-red` issues, the digest, settings checks, Dependabot alerts; the webhook | Contents: write, Pull requests: write, Issues: write, Checks: read, Administration: read, Secrets: read (names only), Dependabot alerts: read, Metadata: read | in `jw-factory` |
| `jw-factory-owner` | the owner's `connect_github` connection: create repos, add repos to installations, apply settings | Administration: write, Contents: write, Metadata: read | **none generated,** so its Administration permission works only through the owner's own connection |

Installation tokens are minted with an explicit `repositories` list and `permissions` subset, and they expire after 1 hour.

### 6.2 Identity

Session work is committed and opened as `jw-factory-agent[bot]`, and merges and `Sync` as `jw-factory[bot]`. PRs opened with an App token start CI, unlike Actions' `GITHUB_TOKEN`.

### 6.3 Rulesets on every managed repo

These stay, as a second line behind the gateway's checks:
- **Default branch:**
  - Restrict updates, with bypass for `jw-factory` and the Repository admin role.
  - Require a PR and CI status checks, and block force pushes.
  - `jw-factory-agent` can't update it, even if the gateway had a bug.
- **Every branch except `factory/**`:** restrict creations, updates and deletions, with the same bypass.
- **This repo (the factory's):** "main requires a pull request" with no bypass, so only the owner merges.

Rulesets on private personal repos may need a paid GitHub plan (E5). `onboard_repo` applies the rulesets, and `Sync` reports drift.

### 6.4 CI secrets

Pushes to `factory/**` run CI, and CI has the repo's secrets (`SONATYPE_*`, `PGP_*`, `MAVEN_*`, `NPM_SECRET`). `publish` refuses workflow changes beyond version lines, which closes the direct route. A build file change can still run code in CI, so:
- Publishing secrets move into a GitHub environment (`release`) whose deployment branch policy allows only the default branch and release tags.
- `Sync` reports repository-level publishing secrets as a finding.

## 7. Security

### 7.1 Trust zones

| Zone | Runs | Trusts |
|---|---|---|
| Owner | claude.ai, local clients | — |
| `jw-factory` | only our ZIO code and its locked dependencies | the owner's authenticated MCP calls; signed webhooks and ops events; nothing a session says beyond what its grants allow |
| Ops sources | our code, parsing untrusted logs | nothing they receive |
| Sandbox (MicroVM) | **untrusted:** the agent, repo builds, dependencies, tests, Docker containers, the repo's MCP servers | nothing |
| GitHub, Heroku, AWS, Kiro, Mailgun | external | — |

### 7.2 Isolation inside a sandbox

**Between sessions:** each session is its own Firecracker VM.

**Inside a session:** the VM has all Linux capabilities, which Docker needs, so the runner separates secrets from untrusted code in the `/run` hook, before anything untrusted starts:

| User | Runs | Holds |
|---|---|---|
| root | the runner: hooks, the gateway link, terminals, `runner mcp`'s bridge, checkout | the session token, in memory; the checkout token for a moment |
| `kiro` | kiro-cli, and the repo's credential-free MCP servers | Kiro access tokens, in kiro-cli's memory |
| `agent` | every terminal command, and its own rootless Docker daemon | nothing secret |

- **`kernel.yama.ptrace_scope=2`:** only a process with `CAP_SYS_PTRACE` may trace another, and `kiro` and `agent` have no capabilities. So neither can read any process's memory. No secret is put in any environment, since same-user processes can read `/proc/<pid>/environ`.
- **Rootless Docker for `agent`:** a rootful daemon's `docker` group would amount to root.
- **The workspace** belongs to a group shared by `kiro` and `agent`. The runner's files are root-only.
- **No AWS credentials** in the VM, and no GitHub token the agent can reach.

What stays reachable on purpose: the session's own gateway tools, through `runner mcp` (scoped by the session's principal), and the internet.

`Check(sandbox-isolation)` verifies this on every image version (7.5).

### 7.3 Credentials

| Credential | Lives in | Scope | Lifetime | If it leaks |
|---|---|---|---|---|
| Factory master secret (derives HMAC keys, encrypts stored tokens) | `jw-factory` config var | factory | rotated by the owner | decrypts stored connection tokens; forges signed messages |
| Owner's MCP access token | the MCP client (claude.ai, local) | `/mcp`, owner only | 365 days, revocable at once | everything the owner MCP can do, until revoked |
| Mailgun API key | `jw-factory` config var | sending email from the factory's domain | rotated | phishing in the factory's name; not sign-ins, since links and codes go only to the owner's address |
| Passkey public keys | schema `auth` | verifying passkeys | until unlinked | nothing |
| `jw-factory-owner` OAuth client secret | `jw-factory` config var | the `connect_github` flow | rotated | with a stolen factory sign-in, reconnecting the owner's GitHub (the owner's GitHub id is still checked) |
| Owner's GitHub user token | schema `auth`, encrypted | the owner's rights through `jw-factory-owner`'s permissions | 8 h, refresh 6 months | repo creation and settings until revoked |
| Upstream MCP credentials (bearer or OAuth) | schema `auth`, encrypted | each upstream server | per provider | that upstream's access, only through the proxy's allowlist |
| `jw-factory` App private key | `jw-factory` config var | control App permissions | rotated | merges where it is a bypass actor |
| `jw-factory-agent` App private key | `jw-factory` config var | agent App permissions | rotated | branches, PRs and comments; no default-branch writes (6.3) |
| Checkout token | runner memory (root), briefly | Contents: read, one repo | 1 h | reading that repo |
| Bootstrap token | the MicroVM's run-hook payload | `/session/open` only | single use, 10 min | nothing once used |
| Session token | runner memory (root) | that session's gateway, one project | until finish or limit | the session's own grants, until revoked |
| Kiro credential (API key; or what E1 shows) | `jw-factory` config var; access tokens in runner and kiro-cli memory | the factory's Kiro subscription | key rotated monthly | credits, capped per session and per day |
| AWS access key for the sandbox API | `jw-factory` config var | run, get, list, suspend, resume and terminate MicroVMs of one image; get and put objects in the cache bucket (to sign URLs) | rotated monthly | cost, bounded by the quota and an AWS Budgets alarm; reading or overwriting caches |
| Presigned cache URL | runner memory (root) | one object under the session's project prefix | about 15 min | that one cache object |
| Image build role | CI, through GitHub OIDC | upload and update the image | per CI run | a new image version |
| Ops source keys (one per source: event signing, config pull) | `jw-factory` and the source | that source's events and config | rotated | forged ops events, still subject to the incident policy |
| Ops triage credential (for the proxy) | `jw-factory` | that source's read-only MCP | rotated | reading its triage data |
| Heroku token for `OpsControl` (heroku-ops) | `jw-factory` config var | a dedicated Heroku account as collaborator on the watched apps (preferred) | expiring, rotated | drains and webhooks, and also scale and resize (DESIGN 10; the narrow client is the control) |
| GitHub webhook secret | `jw-factory` | webhook verification | rotated | forged events, still subject to policy |

### 7.4 What a compromised session can and can't do

Within its time limit (at most 8 hours), it can:
- call its own gateway tools, which check every request: publish to its own `factory/*` branch within the path rules, open PRs, comment on its own repo, request merges the policy decides, and report findings;
- read its project's proxied upstream data, pinned to its own app;
- leave a poisoned dependency in its own project's cache, if it ran from the default branch (8.5);
- use credits and sandbox time within the caps;
- run containers inside its own VM;
- reach any host.

It can't:
- push anything directly, or touch a default branch;
- reach another project, another app's ops data, or another session;
- change settings, configuration, Heroku or AWS;
- read the factory's secrets, the session token, or the Kiro credential (7.2);
- outlive its revocation.

The worst a fully compromised session can do is spam its own repo's issues and PRs within the gateway's rate limits, push bad code to its own `factory/*` branch (CI runs it without publishing secrets), and spend its credit cap.

### 7.5 Verified, not assumed

`Check` jobs run weekly and on every new image version, each failing loudly with a finding:
- **`rulesets`:** `jw-factory-agent` can't update a default branch or a non-`factory/` branch.
- **`gateway-scope`:** a session token for project A gets nothing for project B, from any tool, with any arguments.
- **`publish-rules`:** forbidden paths, branches, sizes and secret patterns are refused.
- **`proxy-pinning`:** a session's proxied triage call for another app is refused.
- **`cache-scope`:** a session's presigned cache URLs can't read or write another project's prefix, and saved archives contain no file matching the secret patterns.
- **`secrets-isolation`:** a `factory/**` job can't read the `release` environment.
- **`sandbox-isolation`:** a terminal command as `agent`, including inside a container, can't find the session token, a Kiro token or the checkout token in any `/proc/*/environ`, in any `/proc/*/mem`, on disk, or through Docker. And `ptrace_scope` is 2.
- **`revocation`:** a finished session's token gets a 401.
- **`config-backup`:** the latest backup is less than 26 hours old.
- **`auth-stores`:** the `zio-auth-server` contract suite against the live schema's implementation, in CI.

## 8. Sandbox environment

### 8.1 Image

A `Dockerfile` on the Lambda MicroVM base (Amazon Linux 2023, x86_64, base image version 3 or later), built by Lambda from a ZIP in S3. This repo's CI uploads it and updates the image through OIDC. It contains:
- the runner (ZIO; hooks on port 9000; `runner mcp`);
- Amazon Corretto 25, and coursier (`cs`) for the other JDKs and launchers repos' CI uses;
- Node.js and npm;
- Docker Engine with its rootless extras, and `dns: ["169.254.169.253"]` in the daemon configuration;
- kiro-cli (`kirocli-x86_64-linux.zip`), pinned by the image version;
- `git`, shellcheck and Pkl. No `gh`: GitHub goes through the gateway;
- Chrome for Testing (linux64) for Lighthouse and kyo-browser tests;
- today's mirror settings from `jdk25-setup.sh`, and warm sbt and Scala compiler caches, within the build's roughly 7 GB of free disk.

### 8.2 Docker

- **On Heroku: no** (M1).
- **In a MicroVM: yes,** with all capabilities, per one early user's report (D3). Experiment E6 is the gate: rootless Docker for `agent`, Testcontainers with Ryuk, Docker DNS, and disk headroom on `medium`.

### 8.3 Memory

kiro-cli 3.1.0 in headless mode peaked at about 510 MB for its process tree (M2). `medium` (4 GB baseline, bursting to 16 GB) leaves room for sbt or Gradle and a few containers.

### 8.4 Network

MicroVMs have public internet access by default through the Lambda-managed `INTERNET_EGRESS` connector, with no VPC:
- **No NAT gateway and no VPC.** A VPC connector is only for private VPC resources, and sessions have none to reach.
- **Data transfer:** inbound (Maven Central, npm, Docker images, clones) is free. Outbound (gateway calls, transcript batches) is small and within AWS's free monthly allowance (E8 measures it).
- **No static egress IP** is needed.
- **UDP is blocked.** Docker containers use the Lambda resolver.
- **No ingress.**

### 8.5 Persistent caches

A fresh MicroVM starts from the image snapshot, so without caches every session downloads its whole dependency graph again. That is slow, it is the traffic Maven Central answers with 429, and it gets worse because Lambda's egress addresses are shared. Docker Hub, for example, limits anonymous pulls to 100 per 6 hours per IP address.

Caches come in two layers:

| Layer | Holds | Written by | Read by |
|---|---|---|---|
| **Image** (read-only) | sbt launchers, common Scala compiler versions, the JDKs, Coursier's most-used artifacts, the Testcontainers base images (Ryuk, Postgres), `npm` itself | this repo's CI, at image build | every session |
| **Project** (read-write) | `~/.cache/coursier`, `~/.ivy2/local`, `~/.sbt/boot`, `~/.cache/sbt` (sbt 2's local cache), `~/.m2/repository`, `~/.gradle/caches` and `~/.gradle/wrapper/dists`, `~/.npm`, and the Docker images the project's tests pulled (`docker save`) | the runner at the end of a session | later sessions of the same project only |

Each cache kind is its own archive, so a Node project never downloads a Gradle cache.

**How it works:**
- **A pluggable store, S3 first:**

  ```scala
  trait Caches:
    def restoreUrl(project: ProjectId, kind: CacheKind, key: CacheKey, fallback: List[CacheKey]): IO[CacheError, Option[PresignedGet]]
    def saveUrl(project: ProjectId, kind: CacheKind, key: CacheKey): IO[CacheError, PresignedPut]
  ```

  The first implementation is a private S3 bucket in the sandbox account, in the same region as the MicroVMs.
- **No AWS credentials in the VM.** The runner asks the gateway (`/session/cache/restore`, `/session/cache/save`). The gateway returns presigned URLs, each valid for about 15 minutes for one object under that project's prefix. The factory's IAM user gets `s3:GetObject` and `s3:PutObject` on the bucket for signing.
- **Keys,** as with GitHub Actions' cache:
  - `cache/<project>/<kind>/<hash of the dependency files>`, where the dependency files are `build.sbt`, `project/*.sbt`, `project/build.properties`, `pom.xml`, `build.gradle*`, `gradle/libs.versions.toml`, lockfiles and `package-lock.json`;
  - falling back to the latest key for that project and kind, then to nothing.
- **Restore** happens after checkout and before the agent starts. The runner, as root, downloads the archive (zstd-compressed tar), unpacks it into the `agent` user's home, and fixes ownership.
- **Save** happens at the end of the session, only if all of these hold:
  - the session ran from the default branch (so `Maintenance`, `Incident` and `Sync` sessions, not `Develop` sessions on someone's experiment);
  - its build ran;
  - the key changed, or the archive grew by more than a set amount.
- **Never cached:** credentials and configuration. The archives exclude `~/.m2/settings.xml`, `~/.gradle/gradle.properties`, `~/.npmrc`, `~/.docker/config.json` and anything matching the secret patterns `publish` uses. Only artifact directories are archived.
- **Eviction:** the latest 2 keys per project and kind, plus an S3 lifecycle rule deleting objects not read in 30 days.
- **Develop sessions** keep their disk across suspend and resume anyway. After parking, they restore like any session.

**Poisoning:** a compromised session could plant a bad artifact in a cache that later sessions trust, because Coursier and Maven verify checksums that sit in the same cache. Two rules limit this:
- **Project caches are per project.** A poisoned cache only reaches later sessions of the same project, whose agent already had the same access to that repo's `factory/*` branches.
- **The image layer is written only by CI** and is read-only to sessions. A shared, writable cross-project cache isn't used for this reason.

`Check(cache-scope)` confirms that a session's presigned URLs can't read or write another project's prefix.

Not in the first version, but available if downloads stay slow or rate-limited (experiment E10):
- AWS CodeArtifact as a pull-through proxy for Maven Central and npm;
- an ECR pull-through cache for Docker Hub.

Both would need a short-lived read token from the gateway in the VM.

## 9. Cost and limits

DESIGN 10 becomes: the factory spends only within caps the owner sets, and code enforces them. The caps (configuration, owner only):
- `max_concurrent_sessions` (default 3);
- `sandbox_hours_per_day` and `sandbox_hours_per_month`;
- `credits_per_session` (enforced by the runner), `credits_per_day` and `credits_per_month`;
- `sessions_per_project_per_day`, `event_sessions_per_project_per_day`, `incident_sessions_per_hour`;
- gateway rate limits per session: comments, PR updates, publishes.

An AWS Budgets alarm on the dedicated account is a second guard, outside the factory.

### 9.1 Sandbox cost (Lambda MicroVMs, x86_64, us-east-1)

Rates from the AWS price list for US East (N. Virginia), read 2026-11-12:

| Item | x86_64 | ARM64, for comparison |
|---|---|---|
| vCPU | $0.0000326557 per vCPU-second ($0.1176 per hour) | $0.0000276944 ($0.0997 per hour) |
| Memory | $0.0000043235 per GB-second ($0.0156 per hour) | $0.0000036667 ($0.0132 per hour) |
| Snapshot read (each launch or resume) | $0.00155 per GB | same |
| Snapshot write (each suspend) | $0.0038 per GB | same |
| Image and suspended-state storage | $0.0001111 per GB-hour ($0.08 per GB-month; images have a 1-week minimum) | same |

Per hour of a running session on x86_64:

| Class | Baseline | At full peak |
|---|---|---|
| `small` (2 GB / 1 vCPU) | $0.149 | $0.595 (8 GB / 4 vCPU) |
| `medium` (4 GB / 2 vCPU) | $0.297 | $1.190 (16 GB / 8 vCPU) |

Peak is billed only while used above the baseline.

A month at 1 to 2 hours of sessions a day (30 to 60 hours), all `medium`, a quarter of the time at full peak (about $0.52 an hour), and about 300 launches of a 6 GB image:

| | 30 h | 60 h |
|---|---|---|
| Compute | $16 | $31 |
| Launch reads (300 × 6 GB × $0.00155) | $3 | $3 |
| Image storage (about 2 versions × 6 GB) | $1 | $1 |
| Develop suspends (say 100 × 6 GB, write plus read) | $3 | $3 |
| Network (8.4) | about $0 | about $0 |
| **About** | **$23 a month** | **$38 a month** |

The weekly `FactoryReview` on `small` adds well under $1 a month in compute.

### 9.2 Other costs

- **`jw-factory`:** one always-on Heroku web dyno.
- **Postgres:** a Heroku Postgres plan with daily backups and at least a week of retention.
- **The Kiro subscription:** credits per session are measured from day one, and `claude-opus-5.5` is listed at a rate multiplier of 2.0.
- **Mailgun:** a low-volume plan (sign-in emails only).
- **AWS:**
  - S3 for image ZIPs (negligible).
  - The cache bucket: about 49 projects × 1 to 3 GB × 2 keys is 100 to 300 GB, at $0.023 per GB-month, so **$2 to $7 a month**. Requests cost cents.
  - Transfer between S3 and the MicroVMs in the same region is expected to be free, as it is for Lambda functions and S3 (E10 confirms it for MicroVMs).

## 10. Later: ACP over HTTP

The runner already speaks ACP to kiro-cli, and the factory already relays sessions. When ACP over HTTP is available, the factory can expose sessions as ACP agents:
- An ACP client (Kiro Crew, IntelliJ, others) connects to `jw-factory`, authenticated with the same OAuth as MCP.
- The factory relays `session/prompt`, `session/update`, `session/cancel` and permission requests between that client and a `Develop` session's runner.

What this asks of the first version:
- Session events are stored as ACP messages.
- `send_message` is `session/prompt` underneath.
- The runner's gateway link can carry ACP messages both ways (a WebSocket from the runner).

zio-acp would gain an HTTP transport next to `Transport.process` and `Transport.pipe`.

## 11. Changes to DESIGN.md and the Skills

| Principle | Change |
|---|---|
| 1. Runtimes | They become `jw-factory` and ops sources on Heroku, and sandboxes in AWS. The owner's tools are MCP clients. |
| 2. One source of truth | The factory's configuration in Postgres. FACTORY.md is deleted. |
| 4. Routines propose, the owner decides | Unchanged in substance. The factory's own changes are proposals the owner approves (5.12). |
| 5. One place | One interface in (MCP), and one list out (`needs_you` and the digest). |
| 6. Improves itself | Findings, measured outcomes, a weekly review and owner-approved proposals. "Nothing reads run logs" ends. |
| 8. Verify | Outcomes per configuration version, transcripts and `Check` jobs. |
| 9. Text from others is data | Built into the tools: text comes back marked with its author and quoted. |
| 10. Never spends | Spends only within owner-set, code-enforced caps, with an AWS budget alarm behind them. |
| 11. Risk tiers | The tier check lives in the merge policy and the incident policy. |
| 12. The watcher holds the least | An ops source detects and reports, and holds no GitHub credentials. The factory decides. |
| 14. Control plane | `jw-factory`, this repo, its database, the App keys, the connections, the AWS account and image pipeline, the ops source keys, and the Heroku token. |
| 15. Authority | It comes from the factory's policy code and the session's grants, not the prompt. |

The Skills change before the pilot:
- **A new `zen-of-factory` Skill** in `jamesward/skills` takes everything factory-specific out of zen-of-projects and the prompts (5.18). zen-of-projects keeps the conventions that hold for every project, inside or outside the factory.
- **GitHub steps** (in zen-of-factory) use the `factory` MCP server's tools: `publish`, `open_or_update_pr`, `ci_status`, `ci_logs`, `issues`, `comment` and `request_merge`, instead of `git push`, `gh` and `mcp__github__*`.
- **Step 8** reports findings with `report_finding` instead of opening `Factory improvement:` PRs.
- **Incident triage** uses the proxied ops tools.
- **Docker** is available, so "start Docker for Testcontainers" holds again.
- **"Testing an unreleased Skill"** (copies in `.factory/skills/`) is replaced by overlays.
- **Claude-specific notes** (ToolSearch, Monitor, GraphQL 403, `add_repo`, `claude/` branches) are removed after the migration.

## 12. Local development and testing

**Requirement:** the whole factory runs and is tested on the owner's machine with no AWS, no GitHub, no Heroku, no Mailgun and no Kiro. Every external service sits behind a ZIO service interface, and development swaps only the layers (zen-of-projects "Testcontainers and test-scoped development"). The development environment can also simulate the world: new issues and comments, CI results, owner merges, production incidents, deploys, and the passage of time.

### 12.1 How it is built

The pattern is toolbook's, where it already works:
- **A test-scope `AppTest`** for each server (`factory`, `ops`) composes the normal routes and services with fake or Testcontainer-backed layers. `./sbta dev` (an alias for `~factory/Test/runReload` plus the ops `AppTest`) starts it.
- **Dev-only routes and tools** live in `src/test/scala` only, so a production binary can't reference them. This matches toolbook's `DevApiRoutes` and `DevLoginRoutes`.
- **Fakes are real implementations of the interfaces,** not mocks, in the style of toolbook's `FakeSandbox`, `FakeAgentLoop`, `McpStub` and `TestNotifier`.
- **Postgres** comes from a Testcontainer: one container per JVM, a fresh database per spec or dev run, with migrations applied (toolbook's `SharedPostgres.freshDatabase`).
- **Time and randomness** come from ZIO's `Clock` and `Random` services. Development uses an adjustable clock, so the scheduler, cooldowns, token expiry and suspend timers can be advanced.

### 12.2 Every external service and its local stand-in

| Interface | Production | Local development | Lite integration test |
|---|---|---|---|
| `GitHub` (Apps, tokens, Git Data API, PRs, issues, checks, rulesets, webhooks) | the three GitHub Apps | **`FakeGitHub`**, described below | `FakeGitHub` served over HTTP, so the real client's wire format is tested against it |
| `Sandboxes` | `LambdaMicrovmSandboxes` | **`LocalProcessSandboxes`**: the runner as a local child process in a temporary workspace (no user separation; for fast iteration) | **`LocalDockerSandboxes`**: the sandbox image built from the same Dockerfile and run in local Docker, so the users, rootless Docker, `ptrace_scope` and hooks are tested without AWS |
| The agent (behind the runner's ACP client) | kiro-cli | **`ScriptedAgent`**: an ACP agent built with zio-acp's `AcpAgent.serve`, which plays a scenario script (tool calls, terminal commands, `publish`, `request_merge`, `report_finding`) with no model and no credits | real kiro-cli, only when the owner's local login is present (an availability gate like toolbook's `RealCliGates`: skipped, never failed) |
| Kiro access tokens | the broker with the factory's Kiro credential | a fake token answer (the scripted agent ignores it) | the owner's local kiro-cli login |
| `Caches` | S3 with presigned URLs | **`LocalCaches`**: a directory, with presigned-style URLs served by a dev route | the S3 implementation against a **Floci** Testcontainer (`io.floci:testcontainers-floci`), which emulates S3 and SigV4 presigning locally |
| The MicroVM API client | AWS | not used | a fake HTTP server for the API's request and response shapes. Floci doesn't emulate Lambda MicroVMs. The real API is exercised only in the gated `integration-tests` module. |
| Ops sources | `jw-heroku-ops` on Heroku | **the real ops `AppTest`** on a local port, pointed at the local factory, plus `FakeOpsSource` for direct events | the protocol's signing, replay and acknowledgement specs |
| Heroku Platform API (`OpsControl`) | Heroku | `FakeHeroku`: drains, webhooks and features in memory | — |
| `Notifier` (sign-in email) | Mailgun | `TestNotifier`, printing links and codes to the log, as in toolbook | — |
| Passkeys | browsers' WebAuthn | a dev shortcut `/login/_owner` (test scope, as toolbook's `/login/_admin`) | a browser test with Playwright's virtual authenticator through chekhov, gated on browser availability |
| Upstream MCP servers | each upstream | `McpStub` (toolbook's pattern): an in-memory upstream with scripted tools | — |
| Skill providers | SkillsJars (Maven Central) and skills.sh (GitHub) | `LocalSkillProvider`: Skills from `FakeGitHub`'s repos by commit, so pins, overlays and bumps work offline | the real providers, gated on network |
| Kiro credits and usage | Kiro's notifications | the scripted agent emits `turn_completion` and `session_usage` with chosen numbers | — |

**`FakeGitHub`:**
- **Repos are real git repositories,** bare repos in a temporary directory managed with JGit. Checkout clones from a local URL, and `publish` applies change sets to them, so code paths that read files, trees and diffs work unchanged.
- **Everything else is in memory:** PRs, issues, comments, labels, check runs and statuses, installations and their repository lists, rulesets and their enforcement. A push or merge that a ruleset forbids is refused, so the gateway's and the merge policy's behavior is tested against the same rules as GitHub's.
- **Webhooks:** every state change emits the same events the real App would send, signed with the dev webhook secret, to the factory's own `/github` endpoint.
- **Authors are typed** (owner, agent bot, control bot, a third party), so DESIGN 9's author marking is tested.

### 12.3 The simulator

Test-scope MCP tools on the local factory (an extra `dev` group on `/mcp`, mounted only by `AppTest`), so the owner or an agent in claude.ai, Kiro or Claude Code can drive a scenario. Each has a matching dev HTTP route for scripted tests.

| Tool | Simulates |
|---|---|
| `dev_create_repo(owner, name, kind, files)` | a managed repo, seeded from a template or a real repo's files |
| `dev_open_issue(repo, title, body, author)` | a new issue, by the owner or a third party, emitting `issues.opened` |
| `dev_comment(target, body, author)` | an issue or PR comment |
| `dev_ci(repo, ref, result, logs?)` | CI finishing with success, failure or a pending state, emitting `check_suite` and `check_run` |
| `dev_owner_merge(pr)` / `dev_owner_close(pr)` / `dev_revert(pr)` | the owner's own actions, which are improvement signals |
| `dev_release(groupArtifact, version)` | a new dependency version, served by a local Maven metadata stub, so maintenance has something to bump |
| `dev_incident(app, condition, samples, duration)` | a production incident: the fake ops source emits started, ongoing and cleared events through the real protocol |
| `dev_drain(app, logFile)` | a log replay through the real ops `AppTest` (today's `Replay` of `heroku logs` dumps), so the real detection runs too |
| `dev_deploy(app, status)` | a release or build webhook |
| `dev_outage(service, duration)` | GitHub, an ops source, the sandbox API or Mailgun failing with 5xx or timeouts, to exercise retries and the alarm |
| `dev_advance_clock(duration)` | time passing: schedules come due, cooldowns expire, tokens lapse, idle sessions suspend |
| `dev_agent_script(kind, script)` | which scripted behavior the next session of that kind plays: a clean bump, a failing build, a forbidden path, a runaway credit spend, a prompt-injection attempt from an issue body |
| `dev_reset` | a fresh database and a fresh `FakeGitHub` |

A full maintenance scenario then runs on a laptop in seconds:
1. `dev_release`.
2. `dev_advance_clock` to the schedule.
3. The scripted agent publishes and opens a PR.
4. `dev_ci(success)`.
5. The merge policy merges.
6. The digest shows it.

### 12.4 The test onion

Following zen-of-james (types, then pure unit tests, then integration tests with fakes, then Testcontainers):
1. **Types:** principals, grants, job kinds and protocol messages make most wrong states unrepresentable.
2. **Pure unit tests:** the merge policy, the incident policy, `publish`'s path and secret rules, cache keys and exclusions, the scheduler's next-due calculation, the runner's permission policy, finding clustering, configuration parsing, Skill pin parsing per provider, and `SkillHash` against skills.sh's test vectors.
3. **Integration with fakes,** with no network and no Docker:
   - the factory plus `FakeGitHub`, `FakeOpsSource`, `LocalProcessSandboxes` and `ScriptedAgent`, end to end through ACP over zio-acp's in-memory `Transport.pipe`;
   - the `Check` jobs (7.5) run here against the fakes too, so `gateway-scope`, `publish-rules`, `proxy-pinning` and `cache-scope` are ordinary tests.
4. **Testcontainers:** Postgres (repositories, migrations, the `zio-auth-server` contract suite), Floci (S3 caches and presigning), and `LocalDockerSandboxes` (the image, users, rootless Docker with a small Testcontainers suite inside it, and the `sandbox-isolation` check).
5. **Gated, real:** kiro-cli with the owner's login (skipped when absent), and an `integration-tests` module for the real MicroVM API, GitHub Apps on a scratch org, and Mailgun. Never part of `testFull` and never run by a maintenance session, as with toolbook's paid suites.

Validation for this repo stays `./sbta "Test / compile; testFull; stage"`, and it needs only local Docker.

## 13. Migration

Each phase has an exit check. A repo moves only when its Claude routine is disabled, so no repo has two updaters (DESIGN 3).

0. **Prerequisites (owner):**
   - the Kiro subscription and credential;
   - the three GitHub Apps;
   - a Mailgun account with a sending domain (SPF and DKIM) and an API key;
   - the `jw-factory` Heroku app with Postgres;
   - a dedicated AWS account with an AWS Budgets alarm, the sandbox IAM user and the CI OIDC role;
   - experiments E1 to E10.
1. **`zio-auth-server`:** done (2026-11-13).
   - Released 0.0.1 and 0.0.2: core, the zio-magnum stores in schema `auth`, the test artifact with the per-interface contract suites; passkeys, step-up, the OAuth connector; consent and the security fixes in 5.14.
   - Toolbook moved onto it through `ApiAuthStore`, in production, with a styled consent page as the one visible change.
   - Met: the contract passes for the in-memory, Postgres and toolbook API stores; toolbook's tests (778) pass; claude.ai, Claude Code and Kiro completed the OAuth flow through consent against toolbook's dev stack.
   - Left: the owner signing in to a test deployment with a passkey, which comes with the factory skeleton.
2. **Skeleton:**
   - first locally (section 12): `AppTest`, `FakeGitHub`, `LocalProcessSandboxes`, `ScriptedAgent`, the simulator, and the end-to-end maintenance scenario passing in `testFull`;
   - then for real: `core`, `factory` and `runner` modules, the image pipeline, the owner MCP and the session gateway with `publish`;
   - a `Check(hello)` session in a MicroVM: runner, kiro-cli with client auth, a `docker run hello-world` as `agent`, a `publish` to a scratch repo, transcript stored.
   - *Exit:* the transcript is readable from claude.ai, the MicroVM is terminated, the token is revoked, and `sandbox-isolation` and `gateway-scope` pass.
3. **Configuration:** import FACTORY.md, the config tools, backups. *Exit:* the configuration matches FACTORY.md, and FACTORY.md is deleted.
4. **Ops:**
   - protocol v1 in `core`, heroku-ops' `FactoryReporter` and config pull, the incident policy, the triage proxy, and `OpsControl`;
   - `FIRE_TOKEN_*` and the `heroku control` routine retired.
   - *Exit:* a replayed incident is decided by the factory and investigated by an `Incident` session through the proxied tools.
5. **Pilot maintenance:**
   - the GitHub tools, the merge policy, rulesets, and zen-of-factory;
   - Skill providers (SkillsJars and skills.sh), Skill manifests and overlays; the skillsjars CLI for the pilot's non-JVM repo, if there is one;
   - on 3 repos: this repo, json-paste, and one Scala library with Testcontainers and publishing secrets moved to an environment;
   - each one's routine disabled first.
   - *Exit:* 3 weeks of correct merges and refusals, Testcontainers suites passing in sessions, and every 7.5 check passing.
6. **Sync, digest, improvement:**
   - the ZIO `Sync` replaces the `factory sync` routine;
   - findings, metrics, the weekly review and proposals.
   - *Exit:* a week of digests that match the old emails, and one approved proposal applied and measured.
7. **Projects and Develop:** `create_project`, `start_session(Develop)`, suspend and resume, parking. *Exit:* a new repo created and worked on entirely from claude.ai.
8. **All repos:**
   - batches of about 10 a week;
   - then delete the routines and the Python scripts, and rewrite DESIGN.md.
   - *Exit:* no enabled routines.

## 14. Measurements, documented facts and experiments

Measured on 2026-11-12:

| | Result |
|---|---|
| M1 Docker in a Heroku dyno | Not possible: no capabilities, `NoNewPrivs`, seccomp, no user namespaces (`unprivileged_userns_clone=0`, `unshare -U` denied), no `/dev/fuse` or `/dev/net/tun`, no cgroup mount, read-only root. |
| M2 kiro-cli memory, headless | About 510 MB peak for the process tree. |
| M3 `--auth-method client` | kiro-cli 3.1.0 sends `_kiro/auth/getAccessToken` with `{}` when no local login exists. It accepts `{accessToken, expiresAt, profileArn}`, and the service required `profileArn`. With a local login present it never asks. |
| M4 Client capabilities | With `terminal` advertised, shell commands come as `terminal/create` (with `env`), and reads as `fs/read_text_file`. A write happened without a client call. Every tool call asks `session/request_permission` unless tools are trusted. |
| M5 Usage reporting | `session_info_update` with `_meta.kiro.kind` `turn_completion` (`promptTurnSummaries[].usage` in credits) and `session_usage`. |
| M6 Model selection | `--model` is refused with `--agent-engine v3`. `session/new` `_meta.kiro.modelId` works. `claude-opus-5.5` is listed at 2.0× credits. |
| M7 zio-acp | 0.1.0 already routes `_`-prefixed agent→client requests to `Client.extMethod`. |
| M8 kiro-cli builds | `kirocli-x86_64-linux.zip`, `kirocli-aarch64-linux.zip` and `kirocli-aarch64-linux-musl.zip` exist (HTTP 200). |
| M9 MicroVM prices | From the AWS price list for US East (N. Virginia): x86_64 $0.0000326557 per vCPU-second and $0.0000043235 per GB-second; ARM64 $0.0000276944 and $0.0000036667; snapshot read $0.0015468 and write $0.0037977 per GB; storage $0.0001111 per GB-hour. |
| M10 Existing seams | toolbook's `login` reaches its data through an `ApiClient` trait. zio-http-mcp has a `TokenVerifier` trait for servers, and an `McpClient` with static headers, OAuth client credentials, and OAuth authorization code with PKCE. |

Measured on 2026-11-13:

| | Result |
|---|---|
| M11 OAuth clients against `zio-auth-server` | Claude Code registers through CIMD (`https://claude.ai/oauth/claude-code-client-metadata`) with a loopback ephemeral-port redirect; Kiro through DCR, with a new client per attempt. Both went through the consent page to a token that mcp accepted, for the platform server and a per-toolbook mount. |
| M12 Escalated tokens in toolbook production | None: 0 clients registered with `role:` scopes, and 0 of 22 live refresh tokens (and 0 live access tokens) with a role beyond their user's. |
| M13 Skill pins | A SkillsJars jar's version is `yyyy_MM_dd-<sha7>` of its commit, with `scm` in the POM (skillsjars `GitService.extractVersion`). `com.jamesward:skills` is versioned by release (`0.0.27`, tag `skills-0.0.27`). skills.sh's `skills-lock.json` v2 records `source`, `skillPath`, `commit` and `computedHash` (its README). |
| M14 Auth-failure logs | zio-http-mcp puts why a bearer token was refused in a log annotation (`error`), which `LogFormat.default` drops. Toolbook's `AppLogging` now prints annotations; the factory's logging does the same. |

From documentation and reports (not yet run by us):

| | Fact | Source |
|---|---|---|
| D1 | MicroVMs: Firecracker isolation per MicroVM, up to 8 hours, suspend and resume with memory and disk, per-second billing, run-hook payload up to 16 KB, the sizes and API rates above | AWS Lambda MicroVM docs and quotas |
| D2 | `additionalOsCapabilities: ["ALL"]` is "applied within the VM isolation boundary" | AWS, "MicroVM images" |
| D3 | "Docker absolutely works" in a MicroVM (a containerd version mismatch fixed with `--containerd=/run/containerd/containerd.sock`); outbound UDP blocked, so containers need `--dns 169.254.169.253`; hooks only on port 9000; about 7.2 GB free disk at image build | Aidan Steele, "Some notes on Lambda MicroVMs", 2026-06-23 |
| D4 | Lambda Managed Instances: containers on EC2, "containers do not provide security isolation between workloads"; 3 instances by default; scales to a minimum, not zero; at most 90 minutes | AWS Lambda Managed Instances docs |
| D5 | MicroVMs have public internet access by default. A VPC egress connector is only for private VPC resources. x86_64 needs base image version 3 or later. | AWS, "Networking" and "MicroVM images" |

To do before phase 2 (the ones needing AWS can't be run from this machine yet: no AWS CLI or account set up here):

| | Question | How |
|---|---|---|
| E1 | With the factory's Kiro API key, what goes in `accessToken` and `profileArn`? Can the key be the access token, or is there a short-lived token to exchange it for? | Run kiro-cli headless with `KIRO_API_KEY` and debug logging, then answer `getAccessToken` with the candidates from the probe client. |
| E2 | Does kiro-cli with client auth write the token to `KIRO_HOME`, logs or a child's environment? | Search the VM's files and `/proc/*/environ` after a session. |
| E3 | Do Kiro's terms allow unattended use of a subscription with an API key? | Kiro's service terms. |
| E4 | Can a GitHub App without a private key do the `connect_github` OAuth flow, create repos with the user token, and add repos to the other Apps' installations? | A test App on a scratch repo. |
| E5 | Rulesets with "Restrict updates" and App bypass on personal private repos and free orgs. | One repo of each kind. |
| E6 | **The go/no-go gate for MicroVMs.** In a `medium` x86_64 MicroVM: rootless Docker for a non-root user, a real Testcontainers suite (Postgres, with Ryuk), Docker DNS, disk headroom, and launch-to-ready time with the full image. | One image, one session, by hand. |
| E7 | Do the users, `ptrace_scope=2` and the clean environments hold inside the VM? Are lifecycle hooks delivered with `NO_INGRESS`? | The `sandbox-isolation` check, run by hand first. |
| E8 | Data transfer out of a typical maintenance session. | The VM's network counters at the end of E6. |
| E9 | `publish` through the Git Data API with a real maintenance change set (lockfiles, many files, a binary): time, API calls, and whether the commit shows as verified. | A scratch repo and a recorded change set. |
| E10 | Caches: restore and save time for a Scala project's Coursier and sbt caches and a Testcontainers image set; whether S3 transfer to and from a MicroVM in the same region is free; and how often Maven Central or Docker Hub rate-limit cold sessions from Lambda's shared addresses. | Two sessions of the same project, cold then warm, and the bill. |

## 15. Open questions

1. **If E6 fails,** MicroVMs are out, and the substrate decision reopens. The next candidate is a fresh EC2 instance per session. No session ever runs without Docker.
2. **`OpsControl`'s Heroku token:** a dedicated Heroku account as a collaborator on the watched apps (preferred), or the owner's `write` token?
3. **`IssueTriage` on `issues.opened`,** or issues only in the weekly maintenance step 9?
4. **Notification volume:** keep watch=All with bot activity, or only `needs-human` and the digest?
5. **AWS region:** us-east-1 (larger quota, closest to Heroku's US region) is assumed.
6. **Session tokens:** factory-minted (proposed: simple, in the factory's schema), or issued by `zio-auth-server` as a per-session client profile, so one token system serves both principals?
7. **The review cadence and size:** weekly on `small` (proposed), or after every N sessions?
8. **Releasing and rolling out Skill changes.** Pins are commits (5.18), so a release is cheap; the cost is the time to publish (a Maven Central deploy for SkillsJars) and keeping every project's pin current. Options:
   - **Project pins only:** after a merge, bump every project's pin; the bumps are routine PRs that merge on green CI. Simple, but a wave of PRs per change.
   - **A factory channel:** the configuration pins each owned Skill source, and factory sessions use that pin instead of the project's. One merge and one configuration change reach every factory session; project pins matter only for work outside the factory and are bumped lazily. The cost: a project's pin and what its factory sessions run can differ.
   - **A channel with staged rollout:** the channel moves through overlays first (some projects, then all), measured by 5.12's before-and-after comparison.
9. **The skillsjars CLI:** its lock format (skills.sh's `skills-lock.json` with a SkillsJars `sourceType`, proposed), and whether it is a native binary, an npm package or both.
10. **More Skill providers:** which beyond SkillsJars and skills.sh (agentskill.sh, Claude Code plugins), and whether a provider with no commit pin can be used at all.

## Sources

- **Claude Code:**
  - scheduled tasks: https://code.claude.com/docs/en/routines
  - authentication: https://code.claude.com/docs/en/authentication
  - terms: https://code.claude.com/docs/en/legal-and-compliance
- **AWS Lambda MicroVMs:**
  - guide: https://docs.aws.amazon.com/lambda/latest/dg/lambda-microvms-guide.html
  - core concepts: https://docs.aws.amazon.com/lambda/latest/dg/microvms-how-it-works.html
  - images: https://docs.aws.amazon.com/lambda/latest/dg/microvms-images.html
  - running: https://docs.aws.amazon.com/lambda/latest/dg/microvms-launching.html
  - networking: https://docs.aws.amazon.com/lambda/latest/dg/microvms-networking.html
  - quotas: https://docs.aws.amazon.com/lambda/latest/dg/gettingstarted-limits.html
  - pricing: https://aws.amazon.com/lambda/pricing/
  - field notes: https://awsteele.com/blog/2026/06/23/some-notes-on-lambda-microvms.html
- **AWS Lambda Managed Instances:** https://docs.aws.amazon.com/lambda/latest/dg/lambda-managed-instances.html
- **Heroku:**
  - limits: https://devcenter.heroku.com/articles/limits
  - dynos: https://devcenter.heroku.com/articles/dynos
- **GitHub App installation tokens:** https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-an-installation-access-token-for-a-github-app
- **Kiro CLI:**
  - headless: https://kiro.dev/docs/cli/headless
  - authentication: https://kiro.dev/docs/getting-started/authentication
  - ACP and the V3 migration: https://kiro.dev/docs/cli/v3/acp-migration
  - permissions: https://kiro.dev/docs/permissions
  - MCP: https://kiro.dev/docs/mcp/configuration
  - Skills: https://kiro.dev/docs/skills
- **Libraries:**
  - zio-acp: `~/projects/zio-acp`
  - zio-http-mcp: `~/projects/zio-http-mcp` (README "Authorization" and "Client")
  - zio-auth-server: https://github.com/jamesward/zio-auth-server (README, `AuthStoreContract`)
  - toolbook `login`: `~/projects/toolbook/login/src/main/scala/ToolBookAuth.scala` (on zio-auth-server) and `api/src/test/scala/ApiAuthStoreContractSpec.scala`
  - toolbook's local development: `~/projects/toolbook/DEV.md`, `DEV_DETAIL.md`, `api/src/test/scala/AppTest.scala`, `FakeSandbox.scala`, `FakeAgentLoop.scala`, `McpStub.scala`
- **Skills:**
  - SkillsJars: https://skillsjars.com, `~/projects/skillsjars` (the deployer's `GitService`, the sbt, Maven and Gradle plugins)
  - `com.jamesward:skills`: https://github.com/jamesward/skills
  - skills.sh and its CLI: https://skills.sh, https://github.com/vercel-labs/skills (README "Lock File Content Hash", `docs/commands.md`)
  - Agent Skills specification: https://agentskills.io
- **Floci** (local AWS emulator, Testcontainers module `io.floci:testcontainers-floci`): https://github.com/floci-io/floci
- **The current factory:** FACTORY.md, DESIGN.md, TODO.md and the scripts in `jamesward/projects`; this repo's `Classifier`, `Detector`, `Fire` and `OpsMcp`.
