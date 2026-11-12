# Anonymous device reports: design

Date: 2026-11-12. Status: draft for the owner's review.

## Why

A device report today opens GitHub's issue form in a browser or the GitHub app. Most people aren't signed in to
GitHub on their phone, so they meet a login wall and a long mobile form, and some give up. As of 2026-11-12 there
are 5 reports from an estimated ~80 installs. The Play build, once published, will reach people even less likely to
have a GitHub account.

The goal is a second way to send the same report with one tap and no account, while keeping development "out in
the open": the report and the whole conversation about it stay public issues on NickLD/CR-BYOB, and the reporter
can follow and answer replies from inside the app without ever touching GitHub.

## Goals

- **"Send Anonymous Report Without GitHub"** becomes the device report dialog's main (positive) button. The
  GitHub path stays, as a smaller blue link in the dialog, "Send with my own GitHub account instead ›" (owner's
  decision: most people have no account). Same fields, same review, same privacy scrubbing.
- The report lands as a normal public issue (`[Device] <scanner> on <phone>`, label `device report`) plus the
  label `anonymous`, filed by a GitHub App bot.
- Reports from one phone can be correlated: each carries a public **reporter tag** derived from a per-install ID.
- **Two-way replies:** the app notices new comments on its reports, shows them, and lets the reporter answer. The
  answer is posted by the bot, only if the sender proves it owns the report.
- A **"Check for replies"** setting, on by default; off stops all reply checks (sending still works).
- Both builds (GitHub and Play) get it. No flavor-specific code.

## Non-goals

- Removing the GitHub path. It keeps working exactly as now, just from the secondary link (and stays the main
  button in builds without a Worker address).
- Background checks or system notifications for replies. The app checks only when it is opened or comes back to
  the foreground (owner's decision). Can be added later; nothing here blocks it.
- Accounts, logins, email, or any private contact channel.
- A per-Worker kill switch: suspending or uninstalling the GitHub App stops anonymous posting at once.
- Storing anything server-side. The Worker keeps no database; GitHub is the only store.

## Anti-goals (failure even if it works)

- Publishing anything the GitHub path doesn't: no serial, Wi-Fi names, passwords, MAC or IP addresses. The Worker
  never logs or publishes the sender's IP.
- Anyone who reads an issue being able to post replies "as" its reporter.
- Issues that look like the owner filed them (hence a GitHub App, not the owner's token).
- A flood of junk issues from one source.

## How it works

### Identity

- On the first anonymous send the app creates a random 128-bit ID (32 lowercase hex chars, e.g. a UUID without
  dashes) and stores it in the app's `bridge` SharedPreferences (not the cache, which Android may clear). It
  survives updates, not reinstalls. It never leaves the phone except in requests to the Worker over HTTPS.
- **Reporter tag** = first 12 hex chars of SHA-256(ID). Public: it goes in every issue and reply the bot posts.
  The full ID acts as the reporter's secret.

### Worker (Cloudflare, free plan)

Lives in this repo under `worker/`, TypeScript, deployed with `wrangler` to the owner's Cloudflare account. No
runtime dependencies. Two endpoints, JSON in and out:

**`POST /report`** `{ "id": "<32 hex>", "title": "...", "body": "..." }` → `{ "number": 42, "url": "..." }`
1. Validate: `id` matches `^[0-9a-f]{32}$`; `title` starts with `[Device]` and is at most 256 chars; `body` is at
   most 60,000 chars (the app's existing `MAX_BODY`) and starts with `### Scanner` (the form layout `body()`
   writes); unknown fields ignored. Anything else → 400.
   *Claude's call:* the Worker URL is public, so anyone can post without the app. The Worker does not re-run the
   app's privacy scrubbing (that would mean porting `LogExport`'s redaction); what it posts is no worse than what
   any GitHub user can post. It does neutralise `@mentions` in title and body (insert a zero-width joiner after
   `@`) so the bot can't be used to ping people.
2. Rate limit per IP with Cloudflare's built-in rate-limiting binding (it only supports 10 s or 60 s windows, so
   e.g. 2 requests per 60 s). Over → 429.
   `shortcut: minute-level limit only, move to a Durable Object or KV counter if spam gets through.`
3. Append a footer: a rule, "Sent anonymously from the CR-BYOB app.", and `Reporter tag: \`<tag>\``.
4. Create the issue as the GitHub App with labels `device report` and `anonymous`. Return `number`, `url`, and
   `bot` (the App's login, e.g. `crbyob-reports[bot]`, from a plain var `GH_BOT_LOGIN`) so the app can recognise
   its own replies. (To verify on first deploy: GitHub silently drops labels on issue creation without push
   access; if that happens with the App's Issues permission, add them with a second call to the labels endpoint.)

**`POST /reply`** `{ "id": "<32 hex>", "issue": 42, "body": "..." }` → `{ "ok": true }`
1. Validate `id`, `issue` is a positive integer, `body` 1 to 10,000 chars; same rate limit.
2. Fetch the issue. Require: it has the `anonymous` label, and its body contains `Reporter tag: \`<tag>\`` where
   `<tag>` is computed from the sent `id`. Otherwise → 403. (Only the bot and maintainers can edit an issue body,
   so the tag in it can be trusted.)
3. Post a comment as the bot: first line `**Reply from the reporter** (tag \`<tag>\`)`, a blank line, the text
   (with `@mentions` neutralised as above).

**GitHub App auth:** sign a JWT (RS256, `iss` = App ID, `iat` = now − 60 s, `exp` = now + 9 min) with WebCrypto,
exchange it for an installation token, call the REST API with it. No token caching (two GitHub calls per request is
fine at this volume). Secrets, set by the owner with `wrangler secret put`: `GH_APP_ID`, `GH_INSTALLATION_ID`,
`GH_APP_KEY` (the App's private key converted once to PKCS#8 with `openssl pkcs8 -topk8 -nocrypt`). Plain var:
`GH_REPO` = `NickLD/CR-BYOB`, `GH_BOT_LOGIN`. Cloudflare's rate-limiting binding is offered on the free plan;
confirm while planning.

**Test:** one `node:test` file (no test framework dependency) covering tag computation, the tag check against an
issue body, and request validation.

### App

All in shared code (`android/CRBYOB/src/...`), reusing what exists:

- **Worker address:** a `REPORT_WORKER_URL` BuildConfig field next to `REPO_URL`, from a Gradle property that
  defaults to empty (read with `.orElse("")`, so CI and release builds never break). Empty = the anonymous button
  and reply checks are hidden. *Claude's call:* the URL is committed to `android/gradle.properties` only after
  the owner has deployed the Worker, in the same release PR, so no release ships a button that can't work.
- **Send:** the positive button of the existing device report dialog (`DeviceReport`) reads "Send Anonymous Report
  Without GitHub" (it reads "Continue on GitHub" as today when the Worker address is empty). The secondary link
  "Send with my own GitHub account instead ›" sits under the privacy line. Both go through the same checks (required fields, the "No scan in this
  report" confirm), then POSTs `title` and the body the GitHub-app path already builds: `body(f, logFor(...,
  githubApp = true, ...))`, which carries the readable log when "Include the log" is on. No zip, no digest. Sent on
  a background thread with `HttpURLConnection`.
  - Success: a short dialog "Report sent" with the issue link ("See it on GitHub", opens in a browser, no login
    needed) and "Replies will show on the dashboard". The issue number is saved.
  - Failure (network, 4xx, 5xx): the error shows in the dialog's existing error line; the person's entries are kept.
- **Disclosure** in the dialog, next to the existing "The report is public on GitHub" line: the anonymous report
  and any replies are public, and it's tagged so later reports from this phone can be matched.
- **Stored** (in `bridge` prefs): the bot login from the first `/report` response; per report the issue number,
  the issue's comment total last seen, the id of the newest developer comment seen, and closed or not.
- **Which comments count:** a comment counts as "the developer replied" (raises the card, shown in the app) when
  its `author_association` is `OWNER`, `MEMBER` or `COLLABORATOR`, **or** its author's `type` is `Bot`. The owner
  plans a bot that triages reports and asks reporters for details and troubleshooting steps, and its comments must
  reach the reporter whether it posts with the owner's token (counts as `OWNER`) or as its own GitHub App (a `Bot`).
  A bot can only comment on CR-BYOB if the owner installed its App there, so this doesn't let strangers in.
  This phone's own replies (author = the stored bot login **and** the reporter-reply marker with this phone's tag;
  both, so the triage bot could even share the App) show as "You" and never raise the card. Comments from other
  GitHub users never raise the card and aren't shown; they're readable through "Open on GitHub". This also keeps
  arbitrary user content out of the Play build.
- **Reply check:** when `MainActivity` resumes, if "Check for replies" is on, there are open reports, and the last
  check was over 15 minutes ago: for each open report, one unauthenticated `GET
  api.github.com/repos/<repo>/issues/<n>` (public, 60 requests/hour per IP) for `comments` and `state`. Only if the
  total went up, a second call to `/issues/<n>/comments` to look for a new developer comment. Closed = stop checking
  it (it stays in the list).
- **Dashboard card** (owner wants a card, not just a text line): while any report has unread comments, show
  "The developer replied to your report ›", in the style of the existing `ReportPrompt` card. Tapping it opens
  the thread.
- **Thread dialog:** fetches `/issues/<n>/comments` and shows the comments that count (above) with author and date. Below: a reply box and Send (POST
  `/reply`), the "Check for replies" switch, and "Open on GitHub". Opening it marks the report as read.
- **Way back:** a small "Your reports (N) ›" line under "Send a device report", shown once at least one anonymous
  report exists. With several reports it opens a list first.
- **Test:** one unit test (alongside `DeviceReportTest`) for the unread/closed bookkeeping and "You" detection.

### Docs and other places it touches

- `README.md`: the device report lines (they say reports are credited to a GitHub username) mention the anonymous
  option and how anonymous reports are credited.
- `docs/using-the-app.md`: the device report section describes both buttons and the reply flow, and what is public.
- `CHANGELOG.md`: an Unreleased entry.
- `worker/README.md`: one-time setup (create the GitHub App with Issues read/write on CR-BYOB only, install it,
  convert the key, create the `anonymous` label, `wrangler deploy`, the three secrets).
- Local triage skill (`.claude/skills/triaging-device-reports`, not in the repo): anonymous issues are authored
  by the bot; credit them as "anonymous report" linking the issue instead of `@user`; comments posted there reach
  the reporter through the app.
- Play Console data-safety form: declare that device reports are sent to the developer's server (owner's task).

## Constraints

- Cloudflare Workers free plan (100k requests/day). GitHub App installation tokens and unauthenticated API limits
  are far above expected volume.
- GitHub caps an issue body at 65,536 chars: 60,000 body + footer stays under it.
- The app has no AndroidX and builds its UI in code; follow that style. `INTERNET` is already in the main manifest.
- Base on `public/main`; ship through the normal release runbook (owner chooses patch or minor).

## Owner's tasks (can't be done by Claude)

Create the Cloudflare account if needed, create and install the GitHub App, set the Worker secrets, deploy, and
update the Play data-safety form.

## Left to the builder

Exact rate-limit numbers, wording of dialogs and the card, where the "Your reports" line sits, how comments render
(plain text or the app's existing `Markdown` renderer), the Gradle property name, and error messages.
