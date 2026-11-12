# CR-BYOB report Worker

A Cloudflare Worker that files anonymous device reports on NickLD/CR-BYOB as a GitHub App, and posts a
reporter's replies after checking the issue carries their tag. `POST /report` and `POST /reply`; no stored state.
`npm test` runs the tests (Node 26, no dependencies).

## One-time setup

1. Create a GitHub App (Settings → Developer settings → GitHub Apps → New). Its name becomes the bot login:
   an App named `crbyob-reports` posts as `crbyob-reports[bot]`. Untick the webhook. Repository permissions:
   Issues: Read and write; nothing else. Then install it on NickLD/CR-BYOB only.
2. Note the App ID (on the App's page) and the installation ID (the number at the end of the installation's
   settings URL, `.../settings/installations/<id>`).
3. Generate a private key on the App's page and convert it to PKCS#8, which WebCrypto needs:
   ```
   openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem
   ```
4. Create the `anonymous` label on NickLD/CR-BYOB (`device report` already exists).
5. If the App's name isn't `crbyob-reports`, set `GH_BOT_LOGIN` in `wrangler.toml` to `<name>[bot]`.
6. From this folder:
   ```
   npx wrangler login
   npx wrangler secret put GH_APP_ID
   npx wrangler secret put GH_INSTALLATION_ID
   npx wrangler secret put GH_APP_KEY < key-pkcs8.pem
   npm run deploy
   ```
   Delete both key files once the secret is set.
7. Smoke test with a fresh ID (the test ID in the code is public: anyone could reply as it):
   ```
   ID=$(openssl rand -hex 16)
   W=https://crbyob-reports.<account>.workers.dev
   curl -X POST $W/report -H 'Content-Type: application/json' \
     -d '{"id":"'$ID'","title":"[Device] Worker smoke test","body":"### Scanner\n\ntest"}'
   ```
   It answers `{"number":…,"url":…,"bot":…}`. Check the issue has both labels. Then reply on it with the number:
   ```
   curl -X POST $W/reply -H 'Content-Type: application/json' -d '{"id":"'$ID'","issue":<number>,"body":"smoke reply"}'
   ```
   It answers `{"ok":true}`. A 403 here while `/report` works means `GH_BOT_LOGIN` doesn't match the App's bot
   login, which turns every reply into a 403. Afterwards delete the smoke issue (or close and lock it).
8. Put the Worker URL in `android/gradle.properties` as `crbyob.reportWorker`.

To stop anonymous posting at once: suspend the App on its installation settings page (step 2's URL, Suspend). The Worker then gets
502s from GitHub and the app shows an error.
