# Anonymous Device Reports Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A "Send Anonymous Report Without GitHub" button that files the device report as a public issue through a
Cloudflare Worker acting as a GitHub App, plus in-app reading of and answering to replies.

**Architecture:** A dependency-free Worker (`worker/`) with `POST /report` and `POST /reply`, signing in as a GitHub
App with WebCrypto. In the app, one new class `AnonReports` (ID, tag, storage, HTTP, comment rules) and one
`ReplyThread` (dialogs); `DeviceReport` gets the button, `MainActivity` the resume check, card and "Your reports"
line.

**Tech Stack:** Worker: TypeScript run directly by Node 26 (`node --test`, native type stripping), deployed with
`npx wrangler`. App: plain Java, no AndroidX, UI built in code, `HttpURLConnection`, `org.json`, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-12-anonymous-device-reports-design.md`

## Global Constraints

- Worker: no runtime dependencies; no stored state; never log or publish the sender's IP.
- ID: 32 lowercase hex chars; regex `^[0-9a-f]{32}$`. Tag = first 12 hex chars of SHA-256(ID as UTF-8).
  Test vector shared by both sides: ID `0123456789abcdef0123456789abcdef` → tag `3eb1bd439947`.
- Report limits: `title` ≤ 256 chars, `body` ≤ 60,000 chars and starts with `### Scanner`. Reply `body` 1–10,000 chars.
- Issue footer, exactly: `\n\n---\nSent anonymously from the CR-BYOB app.\nReporter tag: \`<tag>\``
- Reporter-reply comment first line, exactly: `**Reply from the reporter** (tag \`<tag>\`)` then a blank line, then text.
- Labels: `device report`, `anonymous`.
- `@mentions` neutralised in everything the bot posts: insert U+200D after every `@`.
- Developer comment = `author_association` in {`OWNER`,`MEMBER`,`COLLABORATOR`} **or** `user.type == "Bot"`,
  and not this phone's own reply. Own reply = `user.login` == stored bot login **and** body starts with the
  reporter-reply line carrying this phone's tag.
- Reply checks: only on `MainActivity.onResume`, only if "Check for replies" is on (default on), at most every 15 minutes.
- `REPORT_WORKER_URL` empty → anonymous button, reply checks, card and "Your reports" line are all hidden.
- Prefs file `bridge`. Keys: `anon_id`, `anon_bot`, `anon_reports` (JSON array string), `anon_check_replies`
  (boolean, default true), `anon_last_check` (long ms).
- Main button text, exactly: `Send Anonymous Report Without GitHub`; secondary link: `Send with my own GitHub account instead ›`. Card text: `The developer replied to your report ›`.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and no Claude-Session line.

## Review Focus

1. **Title edited so it no longer starts with `[Device]`** → the Worker prefixes `[Device] ` instead of rejecting
   (a 400 the person can't understand would lose the report). Deviation from the spec's "reject", chosen for that
   reason. Test in Task 1.
2. **Send fails (offline, 429, 5xx)** → the dialog stays open with the person's entries and shows a plain error
   line; nothing is saved as sent. Test: `AnonReportsTest.errorMessage_*` in Task 3.
3. **Someone posts `/reply` with a valid-looking ID to an issue that isn't theirs** → 403, no comment. Test in Task 2.
4. **The issue was deleted or GitHub answers 404/403 during a reply check** → that report is marked closed and
   dropped from checks; no crash, no card. Test: `AnonReportsTest.applyIssue_missingClosesIt` in Task 3.
5. **A stranger's comment that copies the reporter-reply line and this phone's tag** → not shown as "You" and not
   counted (login doesn't match the bot). Test: `AnonReportsTest.isMine_needsBotLogin` in Task 3.

---

### Task 1: Worker rules (pure functions)

**Files:**
- Create: `worker/package.json`, `worker/src/rules.ts`
- Test: `worker/test/rules.test.ts`

**Interfaces:**
- Produces (`worker/src/rules.ts`):
  - `tagOf(id: string): Promise<string>`
  - `validId(id: unknown): id is string`
  - `parseReport(j: any): { title: string; body: string } | string` (string = error message)
  - `parseReply(j: any): { issue: number; body: string } | string`
  - `defang(s: string): string` (U+200D after every `@`)
  - `footer(tag: string): string`
  - `replyText(tag: string, body: string): string`
  - `hasTag(issueBody: string, tag: string): boolean` (true iff the body contains ``Reporter tag: `<tag>` ``)

- [ ] **Step 1: `worker/package.json`** — `"type": "module"`, scripts `"test": "node --test test/"`,
  `"deploy": "npx wrangler deploy"`. No dependencies.
- [ ] **Step 2: Write the failing tests** in `worker/test/rules.test.ts` (`node:test`, `node:assert/strict`):
  - `tagOf('0123456789abcdef0123456789abcdef')` → `'3eb1bd439947'`
  - `validId`: true for 32 lowercase hex; false for 31 chars, uppercase, non-string
  - `parseReport({id, title:'[Device] Otter on Pixel', body:'### Scanner\n\nOtter'})` → object; title `'Otter on Pixel'`
    → title `'[Device] Otter on Pixel'`; body not starting `### Scanner` → string; body 60,001 chars → string;
    title 257 chars → string; empty title → string
  - `parseReply`: `issue` 0, `-1`, `1.5`, `'3'` → string; body `''` or 10,001 chars → string; valid → object
  - `defang('hi @NickLD')` → `'hi @‍NickLD'`
  - `hasTag(body + footer('3eb1bd439947'), '3eb1bd439947')` → true; with `'000000000000'` → false
  - `replyText('abc', 'x')` starts with ``**Reply from the reporter** (tag `abc`)\n\n``
- [ ] **Step 3: Run** `cd worker && npm test` → FAIL (module not found).
- [ ] **Step 4: Implement `worker/src/rules.ts`** — `tagOf` with `crypto.subtle.digest('SHA-256', ...)`; imports in
  tests use the `.ts` extension.
- [ ] **Step 5: Run** `cd worker && npm test` → all pass.
- [ ] **Step 6: Commit** `worker/package.json worker/src/rules.ts worker/test/rules.test.ts` — "Worker: report and reply rules".

### Task 2: Worker endpoints, GitHub App auth, config, setup doc

**Files:**
- Create: `worker/src/index.ts`, `worker/src/github.ts`, `worker/wrangler.toml`, `worker/README.md`
- Test: `worker/test/index.test.ts`

**Interfaces:**
- Consumes: everything in Task 1.
- Produces:
  - `github.ts`: `appToken(env: Env): Promise<string>`; `gh(token: string, method: string, path: string, body?: unknown): Promise<Response>`
    (base `https://api.github.com`, headers `Accept: application/vnd.github+json`, `User-Agent: crbyob-reports`,
    `X-GitHub-Api-Version: 2022-11-28`).
  - `index.ts`: `export default { fetch(req: Request, env: Env): Promise<Response> }` and
    `interface Env { GH_APP_ID: string; GH_INSTALLATION_ID: string; GH_APP_KEY: string; GH_REPO: string; GH_BOT_LOGIN: string; LIMITER: { limit(o: { key: string }): Promise<{ success: boolean }> } }`
  - HTTP contract (the app relies on it): `POST /report` → 200 `{ number, url, bot }`; `POST /reply` → 200
    `{ ok: true }`; errors → `{ error: "<message>" }` with 400 (bad input), 403 (not your report), 404 (unknown
    route), 429 (rate limit), 502 (GitHub failed).

- [ ] **Step 1: Write the failing tests** in `worker/test/index.test.ts`, stubbing `globalThis.fetch` (record calls,
  answer GitHub paths) and passing a fake `env` with `LIMITER.limit` → `{success:true}` and a throwaway RSA key
  generated in the test with `crypto.subtle.generateKey` and exported as PKCS#8 PEM:
  - `/reply` to an issue whose body has another tag → 403, and no `POST .../comments` call was made
  - `/reply` to an issue without the `anonymous` label → 403
  - `/reply` with matching tag and label → 200, comment body equals `replyText(tag, defang(text))`
  - `/report` → 200 `{number, url, bot}`; the issue-create call's body ends with `footer(tag)` and has both labels
  - `LIMITER.limit` → `{success:false}` → 429; `GET /report` → 404; invalid JSON → 400
- [ ] **Step 2: Run** `npm test` → FAIL.
- [ ] **Step 3: Implement `appToken`** — JWT header `{"alg":"RS256","typ":"JWT"}`, payload
  `{iat: now-60, exp: now+540, iss: GH_APP_ID}`, base64url, `crypto.subtle.importKey('pkcs8', ...,
  {name:'RSASSA-PKCS1-v1_5', hash:'SHA-256'})`, sign; `POST /app/installations/{id}/access_tokens` → `.token`.
- [ ] **Step 4: Implement `fetch`** — rate limit key `req.headers.get('CF-Connecting-IP') ?? 'none'` (used only as
  the limiter key, never logged); route; `/report` → `POST /repos/{GH_REPO}/issues` with
  `{title: defang(title), body: defang(body) + footer(tag), labels}`; if the response's `labels` lacks either,
  `POST /repos/{GH_REPO}/issues/{n}/labels` with them. `/reply` → `GET` issue, check label and `hasTag`, then
  `POST .../comments`.
- [ ] **Step 5: `worker/wrangler.toml`** — `name = "crbyob-reports"`, `main = "src/index.ts"`,
  `compatibility_date = "2026-11-01"`, `[vars] GH_REPO = "NickLD/CR-BYOB"`, `GH_BOT_LOGIN = "crbyob-reports[bot]"`,
  and
  ```toml
  [[ratelimits]]
  name = "LIMITER"
  namespace_id = "1001"
  simple = { limit = 2, period = 60 }
  ```
  with the comment `# shortcut: minute-level limit only, move to a Durable Object or KV counter if spam gets through`.
- [ ] **Step 6: `worker/README.md`** — the owner's one-time setup, in order: create GitHub App (name → bot login;
  no webhook; Repository permission Issues: Read and write; install on NickLD/CR-BYOB only); note App ID and
  installation ID; generate a private key and convert:
  `openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem`; create the `anonymous` label; set
  `GH_BOT_LOGIN` in `wrangler.toml` if the App name differs; `npx wrangler login`; `npx wrangler secret put` for
  `GH_APP_ID`, `GH_INSTALLATION_ID`, `GH_APP_KEY`; `npm run deploy`; smoke test with a curl `POST /report`, then
  close that issue. Then put the URL in `android/gradle.properties` as `crbyob.reportWorker`. To stop anonymous
  posting: suspend the App.
- [ ] **Step 7: Run** `npm test` → all pass.
- [ ] **Step 8: Commit** — "Worker: /report and /reply as a GitHub App".

### Task 3: App core — `AnonReports`

**Files:**
- Create: `android/CRBYOB/src/com/crbyob/bridge/AnonReports.java`
- Modify: `android/CRBYOB/build.gradle.kts:99` (add field next to `REPO_URL`)
- Test: `android/CRBYOB/test/com/crbyob/bridge/AnonReportsTest.java`

**Interfaces:**
- Produces (all static, in `final class AnonReports`):
  - `static boolean enabled()` → `!BuildConfig.REPORT_WORKER_URL.isEmpty()`
  - `static String deviceId(SharedPreferences p)` — creates and stores `anon_id` on first call
  - `static String tagOf(String id)`
  - `static boolean isMine(JSONObject comment, String bot, String tag)`
  - `static boolean isDeveloper(JSONObject comment, String bot, String tag)`
  - `static final class Sent { int number; String url; }`
  - `static Sent send(Context c, String title, String body) throws IOException` — POST `/report`, stores `anon_bot`
    and appends `{n, seen:0, dev:0, newest:0, closed:false, unread:false}` to `anon_reports`
  - `static void reply(Context c, int issue, String text) throws IOException` — POST `/reply`
  - `static String errorMessage(int httpCode)` — what the dialog shows: 0 (no connection) →
    "Couldn't reach the report server. Check the connection and try again."; 429 → "Too many reports from this
    network just now. Try again in a minute."; other → "The report server answered " + code + ". Try again later."
  - `static JSONArray reports(SharedPreferences p)` / `static void saveReports(SharedPreferences p, JSONArray a)`
  - `static boolean applyIssue(JSONObject report, int httpCode, JSONObject issue)` — updates `closed` (state
    `closed`, or http 404/410/403 → closed) and returns true when `issue.comments` > `report.seen` (caller then
    fetches comments)
  - `static void applyComments(JSONObject report, JSONArray comments, String bot, String tag)` — sets `seen` to
    the array length and `newest` to the newest developer comment id (0 if none); `unread = true` when
    `newest` > `dev`
  - `static void markRead(JSONObject report)` — `unread=false`, `dev = newest`
  - `static boolean anyUnread(SharedPreferences p)`
  - `static void maybeCheck(Activity a, Runnable onChanged)` — throttle and setting per Global Constraints; runs on
    a background thread; unauthenticated GETs to `https://api.github.com/repos/<GITHUB repo from REPO_URL>/issues/<n>`
    and `/comments?per_page=100`; calls `onChanged` on the UI thread if anything changed.

- [ ] **Step 1: Gradle** — `buildConfigField("String", "REPORT_WORKER_URL", "\"${providers.gradleProperty("crbyob.reportWorker").orElse("").get()}\"")`
  in the same block as `REPO_URL`. Do **not** add the property to `gradle.properties` yet.
- [ ] **Step 2: Write the failing tests** in `AnonReportsTest`:
  - `tagOf("0123456789abcdef0123456789abcdef")` equals `"3eb1bd439947"`
  - `newId()` (package-private static helper `deviceId` uses): matches `^[0-9a-f]{32}$`; two calls differ
  - `isDeveloper`: association `OWNER` → true; `NONE` with `user.type` `Bot` → true; `NONE` + `User` → false;
    own reply (bot login + marker + tag) → false
  - `isMine_needsBotLogin`: marker + tag but `user.login` `"someone"` → false; bot login + marker + other tag → false
  - `applyIssue_missingClosesIt`: http 404 → `closed` true, returns false
  - `applyIssue`: state `open`, comments 3 vs seen 1 → true; comments 1 vs seen 1 → false
  - `applyComments`: a newer developer comment → `unread` true; only own replies added → `unread` stays false
  - `errorMessage_*`: the three messages above
- [ ] **Step 3: Run** `cd android && ./gradlew :CRBYOB:testGithubDebugUnitTest --tests '*AnonReportsTest'` → FAIL.
- [ ] **Step 4: Implement `AnonReports`** — ID from `java.security.SecureRandom` 16 bytes → hex; `MessageDigest`
  SHA-256 for the tag; HTTP like `Updates.java` does (`HttpURLConnection`, timeouts ~15 s, read error stream for
  the code). Log sends and checks with `BLog.log` like the surrounding code (no ID or tag in logs).
- [ ] **Step 5: Run** the same test command → PASS; also `:CRBYOB:testPlayDebugUnitTest`.
- [ ] **Step 6: Commit** — "Anonymous reports: ID, tag, storage and comment rules".

### Task 4: The send button in the device report dialog

**Files:**
- Modify: `android/CRBYOB/src/com/crbyob/bridge/DeviceReport.java` (`Review` dialog builder ~:790, `proceed()` :920, privacy text ~:780)

**Interfaces:**
- Consumes: `AnonReports.enabled()`, `send(...)`, `errorMessage(...)`; existing `body(Fields, String)`,
  `logFor(..., githubApp=true, ...)`, `LogFiles.lines(a)`.

- [ ] **Step 1:** In `Review`, a field `boolean anon`. When `AnonReports.enabled()`: the positive button reads
  `Send Anonymous Report Without GitHub` and sets `anon = true` before `submit()`; under the privacy line add a
  blue link (styled like "See the digest ›") `Send with my own GitHub account instead ›` that sets `anon = false`
  and calls `submit()` (dismissing the dialog when it returns true, as the positive button does). When not
  enabled: no link, the positive button stays `Continue on GitHub` with `anon = false`. `proceed()` becomes
  `if (anon) sendAnon(); else openWith();` so "Send anyway" in `confirmNoStats` keeps working for both.
- [ ] **Step 2:** `sendAnon()` — build `body(f, logFor(BuildConfig.REPO_URL, f, true, fullLog(), l.pinned, l.recent))`,
  disable the dialog's buttons, send on a background thread. Success: dismiss the review, show "Report sent" with
  "See it on GitHub" (opens `Sent.url` in a browser) and the line "Replies will show on the dashboard."; clear any
  saved draft as the GitHub path does. Failure: re-enable the buttons and show `errorMessage(code)` in the existing
  `error` line; the review stays open. Note `submit()` returns true and dismisses — for `anon`, keep the dialog
  open until the send finishes.
- [ ] **Step 3:** When enabled, append to the privacy line: " Sent anonymously, the report and any replies are
  still public, and carry a tag that links reports from this phone."
- [ ] **Step 4: Verify** — `cd android && ./gradlew :CRBYOB:assembleGithubDebug :CRBYOB:assemblePlayDebug
  :CRBYOB:lintGithubDebug` passes; build once with `-Pcrbyob.reportWorker=https://example.invalid` and confirm on
  the API 28 emulator (`crbyob-api28`) that the anonymous button is primary, the GitHub link still opens the form, and a send shows the "Couldn't reach…" error with
  entries kept; without the property the dialog is exactly as before.
- [ ] **Step 5: Commit** — "Device report: Send Anonymous Report Without GitHub".

### Task 5: Replies — resume check, dashboard card, thread dialog, "Your reports"

**Files:**
- Create: `android/CRBYOB/src/com/crbyob/bridge/ReplyThread.java`
- Modify: `android/CRBYOB/src/com/crbyob/bridge/MainActivity.java` (`onResume`; card next to `reportCard` ~:1127;
  footer under the "Tried a scanner or phone?" line ~:991)

**Interfaces:**
- Consumes: Task 3's `maybeCheck`, `anyUnread`, `reports`, `saveReports`, `markRead`, `isMine`, `isDeveloper`,
  `reply`, `errorMessage`.
- Produces: `ReplyThread.openList(Activity a, Runnable onChanged)` (one report → straight to its thread) and
  `ReplyThread.open(Activity a, int issue, Runnable onChanged)`.

- [ ] **Step 1:** `MainActivity.onResume` → `if (AnonReports.enabled()) AnonReports.maybeCheck(this, this::refreshReplies);`
- [ ] **Step 2:** `replyCard`, built like `reportCard` (same background and layout), text
  `The developer replied to your report ›`, tap → `ReplyThread.openList(this, this::refreshReplies)`.
  `refreshReplies()` shows it iff `AnonReports.anyUnread(prefs)` and updates the footer line.
- [ ] **Step 3:** Footer line `Your reports (N) ›` (style of the "Tried a scanner or phone?" line), visible when
  enabled and `reports(prefs).length() > 0`, tap → `openList`.
- [ ] **Step 4:** `ReplyThread` — list dialog ("#42 · open/closed · new replies") when more than one report; thread
  dialog: fetch comments on a background thread, show developer comments and own replies ("You") with login/"You"
  and date, newest last, plain text; a multi-line reply box and "Send" (`AnonReports.reply`, then refetch; errors
  via `errorMessage` in a red line, text kept); a `Switch` "Check for replies" bound to `anon_check_replies`;
  "Open on GitHub" (issue URL). After each fetch the thread calls `applyComments`, then `markRead`, saves, and calls `onChanged`. Hide "Send" on closed issues.
- [ ] **Step 5: Verify** — build and lint both flavors. On the emulator with a debug build pointed at a test repo
  (or by seeding `anon_reports` with a real public issue number via `adb shell run-as`), confirm: card appears for
  an owner comment, opening clears it, switch off stops checks (no requests in logcat after resume).
- [ ] **Step 6: Commit** — "Anonymous reports: replies on the dashboard and in the app".

### Task 6: Docs

**Files:**
- Modify: `README.md` (device report lines ~:87 and ~:107), `docs/using-the-app.md` (device report section ~:313),
  `CHANGELOG.md` (`## [Unreleased]`)
- Modify (local, not in git): `.claude/skills/triaging-device-reports/SKILL.md`

- [ ] **Step 1:** README: mention the anonymous button; anonymous reports are credited "anonymous report" linking
  the issue.
- [ ] **Step 2:** using-the-app: both buttons, what's public (report, replies, tag), where replies show, the
  "Check for replies" switch.
- [ ] **Step 3:** CHANGELOG Unreleased, in the file's existing voice: the button, replies on the dashboard.
- [ ] **Step 4:** Triage skill: issues labelled `anonymous` are authored by the bot; credit as
  `anonymous report ([#N](issue URL))` instead of `@user`; comments posted on them reach the reporter in the app,
  so ask questions there as usual.
- [ ] **Step 5:** Run the repo CI checks locally if a script exists for the changelog job (see `.github/workflows/ci.yml`
  `repo` job); commit the tracked files — "Docs: anonymous device reports".

## After the plan (owner)

Follow `worker/README.md`, then add `crbyob.reportWorker=<url>` to `android/gradle.properties` in the release PR,
and update the Play data-safety form.
