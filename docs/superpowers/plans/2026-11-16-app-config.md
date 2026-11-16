# appConfig — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** settings from a MongoDB `appConfig` document at startup. **Spec:** `docs/superpowers/specs/2026-11-16-app-config-design.md`.
**Tech:** Spring Boot 4.1 `org.springframework.boot.EnvironmentPostProcessor` (registered in `META-INF/spring.factories`),
MongoDB sync driver, JUnit 5, `MockEnvironment`.

## Global Constraints
- Collection `appConfig`, document `_id: "app"`, fields `secretsKeys` (list of `id:base64`), `pagesBaseUrl`.
- Precedence: command line / system properties / environment > appConfig > application.yml.
- A key from the environment is never stored; a generated key is never logged.
- Work in `.worktrees/app-config`; test first; full `./mvnw -q verify` once at the end.

## Review Focus
1. `OMNICHANNEL_SECRETS_KEYS` set: the stored key is ignored at runtime and nothing is written.
2. Mongo down at startup: the app still starts (warning only), within a few seconds.
3. A blank `pagesBaseUrl` or an empty `secretsKeys` list in the document counts as absent.

### Task 1: The loader and its store
**Files:** create `omnichannel-app/src/main/java/me/nageshk/omnichannel/app/config/{AppConfigLoader,AppConfigStore,MongoAppConfigStore}.java`,
`omnichannel-app/src/main/resources/META-INF/spring.factories`; modify `application-test.yml` (`omnichannel.app-config.enabled: false`).
Test: `omnichannel-app/src/test/java/me/nageshk/omnichannel/app/config/{AppConfigLoaderTest,MongoAppConfigStoreIT}.java`.

**Interfaces:** `interface AppConfigStore { Map<String,Object> read(); void saveIfAbsent(String field, Object value); void save(String field, Object value); }`;
`AppConfigLoader implements EnvironmentPostProcessor, Ordered` (order after `ConfigDataEnvironmentPostProcessor`), with a
package-private `void apply(ConfigurableEnvironment env, AppConfigStore store)` the test calls; property source name `appConfig`.

- [ ] Step 1: tests — `AppConfigLoaderTest` (in-memory store, `MockEnvironment`):
  `storedValuesBecomeProperties`, `environmentWinsOverStored` (Review Focus 1, nothing written), `missingKeyIsGeneratedAndStored`
  (`k1:` + 44 base64 chars decoding to 32 bytes; the stored value is the one used), `concurrentGenerationUsesTheStoredKey`
  (store already has a key when saveIfAbsent runs → that key is used), `envPagesUrlIsStored`, `blankValuesCountAsAbsent`
  (Review Focus 3), `disabledDoesNothing`, `storeFailureIsAWarning` (Review Focus 2), `keyNeverLogged`.
  `MongoAppConfigStoreIT` (embedded Mongo of the test context): `saveIfAbsentWritesOnce`, `readReturnsTheDocument`.
- [ ] Step 2: run `./mvnw -q -pl omnichannel-app -am test -Dtest='AppConfigLoaderTest,MongoAppConfigStoreIT' -Dsurefire.failIfNoSpecifiedTests=false` — FAIL.
- [ ] Step 3: implement.
- [ ] Step 4: same command — PASS.
- [ ] Step 5: commit `feat(config): settings from the appConfig collection at startup`.

### Task 2: Docs and the whole build
- [ ] `docs/superpowers/project-rules.md` §2 (8089 deploy needs no variables after the first start; appConfig fields; the key is in the database), `known-limits.md` (move the key out before production), README config note.
- [ ] `./mvnw -q verify` — BUILD SUCCESS.
- [ ] Commit `docs: appConfig settings`.
