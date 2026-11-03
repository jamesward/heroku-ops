# AGENTS.md: heroku-ops

heroku-ops watches the factory's production Heroku apps and handles their incidents: it detects an outage (log drain, Heroku webhooks, URL probes), investigates it with an AI agent (`kiro-cli acp` through [zio-acp](https://github.com/jamesward/zio-acp)), and fixes it within the risk rules. Its design and rules are in [DESIGN.md](https://github.com/jamesward/projects/blob/main/DESIGN.md) (principles 1, 6, 10, 11 and 12) and the plan is in the heroku-ops section of [TODO.md](https://github.com/jamesward/projects/blob/main/TODO.md), both in `jamesward/projects`. Right now it is only the skeleton: a server with `GET /health`.

## Agent tooling

- Follow the `zen-of-projects` Skill (extract with `./sbta extractSkillsJars` into the gitignored `.kiro/skills/`); this file records only project-specific facts and exceptions.
- For Scala 3 and ZIO code (writing or reviewing it), follow `zen-of-scala`.
- For design, domain modeling, effects and testing strategy, follow `zen-of-james`.
- Read the relevant Skills in `.kiro/skills/` before working.
- Changes to heroku-ops follow the principles in DESIGN.md. In particular: no code here may change a Heroku app's size, plan, add-ons or scale (principle 10), and the agent that proposes a change never holds the credential that merges it (principle 12).

## Build, Test & Dev Workflow

- Validation: `./sbta "Test / compile; testFull; stage"` (no Docker or gated tests).
- Run the server: `PORT=8080 ./sbta run`, then `curl localhost:8080/health`.
- Use the MCP server `sbta` (this build's `./sbta --mcp`, configured in `.mcp.json` and `.kiro/settings/mcp.json`, approved in `.claude/settings.json`) for all sbt work: build, test and run with `compile`, `test` (`tests` for specific classes, `full` for all), `run`, `clean` and `publish-local`; inspect the build with `projects`, `test-names`, `main-classes`, `dependencies` and `show`. Use `sbt-task` for any other command (e.g. `reload`) and `list-tasks` to find tasks. Its tools are deferred in Claude Code: load them with ToolSearch (search `sbta`). Don't run `sbt` or `./sbta` in a shell while the MCP tools are available; if the server is unavailable, say so and fall back to `./sbta <command>`.
- After editing Scala 3 sources, check them with `check` (pass the edited files: a fast type check without compiling; `"scope":"module"` after changing an API), then run `compile` and `test` before declaring the work done.
- For Scala symbols use `glob-search` (find or list), `inspect` (members and signatures) and `symbol-location` (source location) rather than text search or guessing APIs. JavaDoc/ScalaDoc lookups are the proxied javadocs.dev tools of the same server.

## Production

- Heroku app: `jw-heroku-ops`. Scala servers use exactly the buildpacks `heroku/jvm`, then `https://github.com/jamesward/buildpack-scala` (zen-of-projects "Heroku deploys"). Record the URL here once the app is wired.
- Deploys: Heroku GitHub auto-deploy from `main`, after CI passes. Not connected yet (the owner connects the repo in the Heroku dashboard).

## Maintenance routine

`.factory/MAINTENANCE.md` (weekly once the repo is in the factory's schedule tables), following the `zen-of-projects` Skill.

## Exceptions

None.
