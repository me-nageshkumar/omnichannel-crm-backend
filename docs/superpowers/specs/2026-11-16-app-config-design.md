# appConfig: settings stored in MongoDB (pre-production stop-gap)

Status: approved by the user 2026-11-16 ("Until we go for production deployment, keep key in appConfig for now. Later
before going live we will see another approach which fits.").

## Intent
Start the app on 8089 with a plain `java -jar`: the secrets sealing key (`OMNICHANNEL_SECRETS_KEYS`) and the pages base
URL (`OMNICHANNEL_PAGES_BASE_URL`) come from a MongoDB collection instead of environment variables.

## Approaches
- **Chosen:** an early property source read from MongoDB at startup (Spring `EnvironmentPostProcessor`), below
  environment variables and above `application.yml`. Every existing consumer keeps reading ordinary properties.
- Rejected: services that read Mongo lazily (every consumer of the two properties would change, and the secrets class
  binds before any repository exists); a settings page and live reload (not asked for; work for a stop-gap).

## Design
1. Collection `appConfig`, one document `{_id: "app", secretsKeys: ["k1:<base64>"], pagesBaseUrl: "https://…"}`.
2. Read at startup with `spring.mongodb.uri` and a 3-second timeout; mapped to `omnichannel.secrets.keys` and
   `omnichannel.pages.base-url`; placed right after the system environment, so environment variables and command-line
   arguments still win (the production path later).
3. First start: no key in the environment and none stored → generate `k1:<32 random bytes, base64>` and store it
   (a race between two starts converges on the stored one). `OMNICHANNEL_PAGES_BASE_URL` set in the environment → stored
   (so a new tunnel URL is remembered for the next plain start). A key given in the environment is never written to
   the database.
4. Changes apply after a restart; edit with mongosh. Mongo unreachable → a warning, the app starts as before.
   `omnichannel.app-config.enabled=false` turns it off (the test profile does).
5. The key is never logged. Known limit: the key now lives in the database and in every mongodump; move it out before
   production.

## Left to the builder
Class names and package; log wording.
