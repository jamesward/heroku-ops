# TODO

## Monitoring the monitor

heroku-ops watches the other apps and itself through the webhook and the probe, but not through its logs (no drain to itself). The only things that tell the owner it is in trouble are its own alarm and the Heroku failed-requests alert on `jw-heroku-ops`. Open items:

- **Verify the alert when heroku-ops is down.** The apps keep posting drains and webhooks to a dead or restarting dyno, and the router answers with H10/H12/H13 errors, which should raise the failed-requests percentage and trip the alert. Not verified. Check it once with a deliberate outage the owner chooses (a stopped dyno is a change to the app's formation, so not something a routine or the factory does).
- **A blind heroku-ops.** It can be up and useless: the factory table failing to load for hours (it keeps the last good list and only logs a warning), drain secrets that stopped matching (`rejected` rises), a watched app that sends no logs although its probe is up. None of these raises the alarm today. Candidates: alarm when `rejected` grows for N minutes, when the table has not been loaded for an hour, and when a watched app's drain has been silent for 10 minutes while its probe is up.
- **The alarm only covers undelivered incidents.** Failed fires (`fireFailures`) count, and the incident is recorded as undelivered, so those raise it. A routine that starts and then produces no issue does not. For public repos, check (unauthenticated) that the incident issue appeared after a successful fire.
- **State is in memory.** Heroku restarts the dyno about daily and every deploy, which clears the incidents, the open-alert memory and the alarm. An undelivered incident should survive a restart: Heroku Key-Value Store (Valkey) is the planned home. Until then the alarm can stop early and an ongoing condition alerts again.
- **An independent check.** Something outside Heroku that requests `/health` and `/alarm` (the earlier idea was a scheduled GitHub Actions job that opens an issue) covers a Heroku-wide outage, which the Heroku alert cannot. Decide whether it is worth it.
- **The alert's settings.** Confirm that the threshold and the sensitivity catch the 30-minute alarm without firing on normal traffic, and that the alarm's share of requests stays large enough as watched apps and their traffic grow (javadocs alone is about 100 requests a minute).
