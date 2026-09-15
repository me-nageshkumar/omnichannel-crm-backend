# Server-Delivered Page Script Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the WhatsApp page script from the Spring Boot backend on every connect so the Chrome extension becomes a minimal shell that never needs reloading for page-script changes.

**Architecture:** The npm build writes `page.js` + `manifest.json` into the server resources (committed). A `ScriptCatalog` validates and serves them via `GET /api/scripts[/{name}]` and advertises the manifest in the `hello` reply. The worker fetches on hash change, verifies SHA-256, caches in `chrome.storage`, and sends the code to the content relay, which injects it through a `blob:` URL `<script>` tag (allowed by WhatsApp's CSP).

**Tech Stack:** esbuild (two-pass build with hash stamping), Node 24 tests, Spring Boot 3.5 (existing server), Chrome MV3.

**Spec:** `docs/superpowers/specs/2026-09-15-remote-page-script-design.md`

## Global Constraints

- Branch `feat/remote-scripts` (created from `feat/chrome-extension-bridge`). Commit with `--no-verify` after `printf '%s\n' "$MSG" | npx --no-install commitlint` (macOS husky hook is broken, see the PoC plans). Conventional Commits, end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Extension package must contain no page script; only `dist/background.js`, `dist/content.js`, `dist/popup.js`.
- Resource paths: `whatsapp-backend/src/main/resources/wabridge/scripts/page.js` and `.../manifest.json`. Manifest shape: `{"page": {"version": "<12 hex>", "sha256": "<64 hex>", "size": <bytes>, "builtAt": "<ISO-8601>"}}`.
- Page script build stamp `__WABRIDGE_BUILD__` equals the manifest `version`.
- Port control messages between worker and relay: `{type:'inject', name, version, code}` (worker→relay), `{type:'injected', name, version}` and `{type:'inject-failed', name, version, message}` (relay→worker). Everything else on the port remains a protocol envelope.
- Server requests are answered `WHATSAPP_NOT_READY` until the tab has reported `injected` (or emitted `ready`).
- Run extension commands from `whatsapp-web-extension/`, server commands from `whatsapp-backend/` with `./mvnw`. Run `npx --no-install prettier --write` and `npx --no-install eslint extension` from the repo root before committing JS.

---

## File Structure

```
whatsapp-web-extension/
  build.mjs                          # modify: shell → dist/, page → server resources (two-pass stamp)
  src/build/stamp.js                 # new: finalizePageScript(text) → {code, version, sha256}; sha256Hex
  src/background/scripts.js          # new: ensurePageScript(), ScriptIntegrityError
  src/background/connection.js       # modify: onHello option
  src/background/router.js           # modify: tab readiness gating, toTab()
  src/background/storage.js          # modify: loadPageScript/savePageScript
  src/background/index.js            # modify: fetch/inject wiring, remove page executeScript
  src/content/relay.js               # modify: handle inject control messages via blob URL
  src/popup/popup.js                 # modify: Page script line
  manifest.json                      # unchanged permissions; no page.js in package
  tests/stamp.test.js, scripts.test.js, connection.test.js (+1), router.test.js (modify)
server/src/main/resources/wabridge/scripts/page.js        # generated, committed
server/src/main/resources/wabridge/scripts/manifest.json  # generated, committed
server/src/main/java/me/nageshk/whatsapp/scripts/ScriptCatalog.java
server/src/main/java/me/nageshk/whatsapp/api/ScriptsController.java
server/src/main/java/me/nageshk/whatsapp/ws/BridgeWebSocketHandler.java   # modify: hello reply
server/src/test/java/me/nageshk/whatsapp/scripts/ScriptCatalogTest.java
server/src/test/java/me/nageshk/whatsapp/api/ScriptsApiIT.java
server/src/test/java/me/nageshk/whatsapp/ws/BridgeWebSocketIT.java         # modify: hello has scripts
```

---

### Task 1: Build stamping and sync into server resources

**Files:**
- Create: `whatsapp-web-extension/src/build/stamp.js`, `whatsapp-web-extension/tests/stamp.test.js`
- Modify: `whatsapp-web-extension/build.mjs`
- Generated + committed: `whatsapp-backend/src/main/resources/wabridge/scripts/page.js`, `whatsapp-backend/src/main/resources/wabridge/scripts/manifest.json`

**Interfaces:**
- Produces `finalizePageScript(text: string): { code, version, sha256, size }` where `version = sha256Hex(text).slice(0, 12)` and `code = text.replaceAll('__WABRIDGE_VERSION__', version)`, `sha256 = sha256Hex(code)`, `size = Buffer.byteLength(code)`.
- Produces `sha256Hex(text: string): string` (node:crypto).
- The build writes both resource files; `--no-sync` skips them; `--watch` rebuilds shell and page on change.

- [ ] **Step 1: Write the failing test**

`whatsapp-web-extension/tests/stamp.test.js`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { finalizePageScript, sha256Hex } from '../src/build/stamp.js';

test('sha256Hex matches node crypto', () => {
    const expected = createHash('sha256').update('abc').digest('hex');
    assert.equal(sha256Hex('abc'), expected);
});

test('finalizePageScript stamps the version and reports the final hash', () => {
    const text = 'const BUILD = "__WABRIDGE_VERSION__"; console.log(BUILD, "__WABRIDGE_VERSION__");';
    const out = finalizePageScript(text);
    assert.match(out.version, /^[0-9a-f]{12}$/);
    assert.equal(out.version, sha256Hex(text).slice(0, 12));
    assert.equal(out.code.includes('__WABRIDGE_VERSION__'), false);
    assert.equal((out.code.match(new RegExp(out.version, 'g')) ?? []).length, 2);
    assert.equal(out.sha256, sha256Hex(out.code));
    assert.equal(out.size, Buffer.byteLength(out.code));
});

test('finalizePageScript is deterministic for identical input', () => {
    const a = finalizePageScript('x "__WABRIDGE_VERSION__"');
    const b = finalizePageScript('x "__WABRIDGE_VERSION__"');
    assert.deepEqual(a, b);
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd whatsapp-web-extension && npm test`
Expected: `stamp.test.js` fails with module not found.

- [ ] **Step 3: Implement stamp.js**

`whatsapp-web-extension/src/build/stamp.js`:

```js
// Build-time helpers (Node only): stamp the page script with a content-derived version.
import { createHash } from 'node:crypto';

export const VERSION_PLACEHOLDER = '__WABRIDGE_VERSION__';

export const sha256Hex = (text) => createHash('sha256').update(text).digest('hex');

/** @returns {{ code: string, version: string, sha256: string, size: number }} */
export function finalizePageScript(text) {
    const version = sha256Hex(text).slice(0, 12);
    const code = text.replaceAll(VERSION_PLACEHOLDER, version);
    return { code, version, sha256: sha256Hex(code), size: Buffer.byteLength(code) };
}
```

- [ ] **Step 4: Rewrite build.mjs**

`whatsapp-web-extension/build.mjs`:

```js
import { build, context } from 'esbuild';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { finalizePageScript, VERSION_PLACEHOLDER } from './src/build/stamp.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const watch = process.argv.includes('--watch');
const sync = !process.argv.includes('--no-sync');
const SCRIPTS_DIR = path.resolve(here, '../server/src/main/resources/wabridge/scripts');

const common = {
    bundle: true,
    format: 'iife',
    platform: 'browser',
    target: ['chrome120'],
    sourcemap: false,
    logLevel: 'info',
};

// Shell bundles: shipped inside the extension package.
const shell = ['content', 'background', 'popup'].map((name) => ({
    ...common,
    entryPoints: [`src/${name === 'content' ? 'content/relay' : name === 'background' ? 'background/index' : 'popup/popup'}.js`],
    outfile: `dist/${name}.js`,
}));

// Page script: bundled in memory, stamped with a content hash, written to the server resources.
const pageOptions = {
    ...common,
    entryPoints: ['src/page/inject.js'],
    write: false,
    outdir: 'dist-page-tmp',
    define: { __WABRIDGE_BUILD__: JSON.stringify(VERSION_PLACEHOLDER) },
};

async function buildPage() {
    const result = await build(pageOptions);
    const text = result.outputFiles[0].text;
    const out = finalizePageScript(text);
    if (!sync) {
        console.log(`page script ${out.version} (${out.size} bytes) built, sync skipped`);
        return out;
    }
    await mkdir(SCRIPTS_DIR, { recursive: true });
    const manifestPath = path.join(SCRIPTS_DIR, 'manifest.json');
    let previous = {};
    try {
        previous = JSON.parse(await readFile(manifestPath, 'utf8'));
    } catch {
        // first build
    }
    const unchanged = previous.page?.sha256 === out.sha256;
    const manifest = {
        page: {
            version: out.version,
            sha256: out.sha256,
            size: out.size,
            builtAt: unchanged ? previous.page.builtAt : new Date().toISOString(),
        },
    };
    await writeFile(path.join(SCRIPTS_DIR, 'page.js'), out.code);
    await writeFile(manifestPath, `${JSON.stringify(manifest, null, 4)}\n`);
    console.log(`page script ${out.version} (${out.size} bytes) → ${path.relative(process.cwd(), SCRIPTS_DIR)}${unchanged ? ' (unchanged)' : ''}`);
    return out;
}

if (watch) {
    const contexts = await Promise.all(shell.map((o) => context(o)));
    await Promise.all(contexts.map((c) => c.watch()));
    const pageCtx = await context({
        ...pageOptions,
        plugins: [{ name: 'sync-page', setup: (b) => b.onEnd(() => buildPage().catch(console.error)) }],
    });
    await pageCtx.watch();
    console.log('watching…');
} else {
    await Promise.all(shell.map((o) => build(o)));
    await buildPage();
}
```

- [ ] **Step 5: Build, verify outputs, lint**

Run: `cd whatsapp-web-extension && rm -f dist/page.js && npm test && npm run build`
Expected: tests pass (stamp tests included); output lists `dist/content.js`, `dist/background.js`, `dist/popup.js` and a line `page script <12hex> (<n> bytes) → ../server/src/main/resources/wabridge/scripts`. `ls dist` shows no `page.js`.

Run:
```bash
cd /Users/nagesh/Projects/WhatsAppWebJS/SCM/whatsapp-web.js
shasum -a 256 server/src/main/resources/wabridge/scripts/page.js | cut -c1-64
python3 -c 'import json;print(json.load(open("server/src/main/resources/wabridge/scripts/manifest.json"))["page"]["sha256"])'
grep -c "$(python3 -c 'import json;print(json.load(open("server/src/main/resources/wabridge/scripts/manifest.json"))["page"]["version"])')" server/src/main/resources/wabridge/scripts/page.js
npx --no-install eslint extension
```
Expected: the two hashes are identical; the version appears at least once in `page.js`; eslint clean.

Add to `.prettierignore` (repo root): `whatsapp-backend/src/main/resources/wabridge/scripts` is already covered by `server`; nothing to do. Add to `eslint.config.mjs` ignores: nothing (server is ignored).

- [ ] **Step 6: Commit**

```bash
git add extension/build.mjs extension/src/build extension/tests/stamp.test.js server/src/main/resources/wabridge
git commit --no-verify -m "feat(extension): build stamps the page script and syncs it into server resources

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: ScriptCatalog (server)

**Files:**
- Create: `whatsapp-backend/src/main/java/me/nageshk/whatsapp/scripts/ScriptCatalog.java`
- Test: `whatsapp-backend/src/test/java/me/nageshk/whatsapp/scripts/ScriptCatalogTest.java`

**Interfaces:**
- `record Script(String name, String version, String sha256, byte[] bytes)`
- `ScriptCatalog(ObjectMapper)` (Spring) and package-private `ScriptCatalog(ObjectMapper, Resource manifest, Function<String, Resource> scriptLoader)`
- `JsonNode manifest()`, `Optional<Script> find(String name)`, `static String sha256Hex(byte[])`
- Throws `IllegalStateException` at construction on missing resource or hash mismatch.

- [ ] **Step 1: Write the failing tests**

`whatsapp-backend/src/test/java/me/nageshk/whatsapp/scripts/ScriptCatalogTest.java`:

```java
package me.nageshk.whatsapp.scripts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

class ScriptCatalogTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final byte[] CODE = "console.log('hi')".getBytes(StandardCharsets.UTF_8);

    private Resource manifest(String sha) {
        String json = "{\"page\":{\"version\":\"abcdef123456\",\"sha256\":\"" + sha + "\",\"size\":" + CODE.length + ",\"builtAt\":\"2026-09-15T00:00:00Z\"}}";
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void loadsScriptsListedInManifestAndExposesManifest() {
        String sha = ScriptCatalog.sha256Hex(CODE);
        ScriptCatalog catalog = new ScriptCatalog(mapper, manifest(sha), Map.of("page", new ByteArrayResource(CODE))::get);

        assertThat(catalog.manifest().get("page").get("sha256").asText()).isEqualTo(sha);
        assertThat(catalog.find("page")).isPresent();
        assertThat(catalog.find("page").get().version()).isEqualTo("abcdef123456");
        assertThat(catalog.find("page").get().bytes()).isEqualTo(CODE);
        assertThat(catalog.find("nope")).isEmpty();
    }

    @Test
    void failsFastOnHashMismatch() {
        assertThatThrownBy(() -> new ScriptCatalog(mapper, manifest("0".repeat(64)), Map.of("page", new ByteArrayResource(CODE))::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("page");
    }

    @Test
    void failsFastOnMissingScriptResource() {
        String sha = ScriptCatalog.sha256Hex(CODE);
        assertThatThrownBy(() -> new ScriptCatalog(mapper, manifest(sha), name -> null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void committedResourcesAreConsistent() {
        // Guards the generated files in src/main/resources against hand edits or a forgotten rebuild.
        ScriptCatalog catalog = new ScriptCatalog(mapper);
        assertThat(catalog.find("page")).isPresent();
        assertThat(catalog.manifest().get("page").get("version").asText()).hasSize(12);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd whatsapp-backend && ./mvnw -q test -Dtest=ScriptCatalogTest`
Expected: compilation error, `ScriptCatalog` missing.

- [ ] **Step 3: Implement ScriptCatalog**

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/scripts/ScriptCatalog.java`:

```java
package me.nageshk.whatsapp.scripts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** Scripts the server delivers to the extension, loaded from the classpath and hash-verified at startup. */
@Component
public class ScriptCatalog {

    public record Script(String name, String version, String sha256, byte[] bytes) {}

    private static final Logger log = LoggerFactory.getLogger(ScriptCatalog.class);
    private static final String BASE = "wabridge/scripts/";

    private final JsonNode manifest;
    private final Map<String, Script> scripts = new LinkedHashMap<>();

    @Autowired
    public ScriptCatalog(ObjectMapper mapper) {
        this(mapper, new ClassPathResource(BASE + "manifest.json"), name -> new ClassPathResource(BASE + name + ".js"));
    }

    ScriptCatalog(ObjectMapper mapper, Resource manifestResource, Function<String, Resource> scriptLoader) {
        try (InputStream in = manifestResource.getInputStream()) {
            this.manifest = mapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read script manifest " + manifestResource, e);
        }
        manifest.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            JsonNode meta = entry.getValue();
            Resource res = scriptLoader.apply(name);
            if (res == null || !res.exists()) {
                throw new IllegalStateException("script resource missing for '" + name + "'");
            }
            byte[] bytes;
            try (InputStream in = res.getInputStream()) {
                bytes = in.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException("cannot read script '" + name + "'", e);
            }
            String actual = sha256Hex(bytes);
            String expected = meta.path("sha256").asText();
            if (!actual.equals(expected)) {
                throw new IllegalStateException("script '" + name + "' sha256 mismatch: manifest=" + expected + " actual=" + actual
                        + " (run `npm run build` in extension/ and commit the resources)");
            }
            scripts.put(name, new Script(name, meta.path("version").asText(), actual, bytes));
            log.info("script '{}' version {} ({} bytes) ready", name, meta.path("version").asText(), bytes.length);
        });
    }

    public JsonNode manifest() {
        return manifest;
    }

    public Optional<Script> find(String name) {
        return Optional.ofNullable(scripts.get(name));
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cd whatsapp-backend && ./mvnw -q test -Dtest=ScriptCatalogTest`
Expected: 4 tests pass (the consistency test reads the files committed in Task 1).

- [ ] **Step 5: Commit**

```bash
git add server/src
git commit --no-verify -m "feat(server): ScriptCatalog loads and hash-verifies deliverable scripts

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Scripts endpoints and manifest in the hello reply

**Files:**
- Create: `whatsapp-backend/src/main/java/me/nageshk/whatsapp/api/ScriptsController.java`, `whatsapp-backend/src/test/java/me/nageshk/whatsapp/api/ScriptsApiIT.java`
- Modify: `whatsapp-backend/src/main/java/me/nageshk/whatsapp/ws/BridgeWebSocketHandler.java` (constructor + `handleRequest`), `whatsapp-backend/src/test/java/me/nageshk/whatsapp/ws/BridgeWebSocketIT.java` (`helloUpdatesStateAndUnknownMethodIsRejected`), `whatsapp-backend/README.md`

**Interfaces:**
- `GET /api/scripts` → manifest JSON. `GET /api/scripts/{name}` → script bytes, `Content-Type: application/javascript;charset=UTF-8`, `ETag: "<sha256>"`, `Cache-Control: private, max-age=0, must-revalidate`; `If-None-Match` equal → 304; unknown → 404 `{code:"NOT_FOUND"}`.
- `hello` response result: `{ "ok": true, "scripts": <manifest> }`.

- [ ] **Step 1: Write the failing tests**

`whatsapp-backend/src/test/java/me/nageshk/whatsapp/api/ScriptsApiIT.java`:

```java
package me.nageshk.whatsapp.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import me.nageshk.whatsapp.auth.JwtService;
import me.nageshk.whatsapp.scripts.ScriptCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScriptsApiIT {

    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired ScriptCatalog catalog;

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt.issue("demo").token());
        return h;
    }

    @Test
    void manifestListsPageScript() {
        ResponseEntity<JsonNode> res = rest.exchange("/api/scripts", HttpMethod.GET, new HttpEntity<>(auth()), JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("page").get("sha256").asText()).isEqualTo(catalog.find("page").orElseThrow().sha256());
    }

    @Test
    void scriptIsServedAsJavascriptWithEtagAnd304() {
        String sha = catalog.find("page").orElseThrow().sha256();
        ResponseEntity<byte[]> res = rest.exchange("/api/scripts/page", HttpMethod.GET, new HttpEntity<>(auth()), byte[].class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getContentType().toString()).startsWith("application/javascript");
        assertThat(res.getHeaders().getETag()).isEqualTo("\"" + sha + "\"");
        assertThat(ScriptCatalog.sha256Hex(res.getBody())).isEqualTo(sha);

        HttpHeaders h = auth();
        h.setIfNoneMatch("\"" + sha + "\"");
        ResponseEntity<byte[]> cached = rest.exchange("/api/scripts/page", HttpMethod.GET, new HttpEntity<>(h), byte[].class);
        assertThat(cached.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void unknownScriptIs404AndNoTokenIs401() {
        ResponseEntity<JsonNode> missing = rest.exchange("/api/scripts/nope", HttpMethod.GET, new HttpEntity<>(auth()), JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> anon = rest.getForEntity("/api/scripts/page", String.class);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

In `BridgeWebSocketIT.helloUpdatesStateAndUnknownMethodIsRejected`, after `assertThat(reply.result().get("ok").asBoolean()).isTrue();` add:

```java
            assertThat(reply.result().get("scripts").get("page").get("sha256").asText()).hasSize(64);
```

- [ ] **Step 2: Run to verify failure**

Run: `cd whatsapp-backend && ./mvnw -q test -Dtest='ScriptsApiIT,BridgeWebSocketIT'`
Expected: `ScriptsApiIT` 404s (no controller); the hello assertion fails (no `scripts`).

- [ ] **Step 3: Implement ScriptsController**

`whatsapp-backend/src/main/java/me/nageshk/whatsapp/api/ScriptsController.java`:

```java
package me.nageshk.whatsapp.api;

import com.fasterxml.jackson.databind.JsonNode;
import me.nageshk.whatsapp.scripts.ScriptCatalog;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the extension's server-delivered scripts (see ScriptCatalog). */
@RestController
@RequestMapping("/api/scripts")
public class ScriptsController {

    private static final MediaType JAVASCRIPT = new MediaType("application", "javascript", java.nio.charset.StandardCharsets.UTF_8);

    private final ScriptCatalog catalog;

    public ScriptsController(ScriptCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public JsonNode manifest() {
        return catalog.manifest();
    }

    @GetMapping("/{name:[a-z0-9_-]+}")
    public ResponseEntity<?> script(
            @PathVariable String name,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        return catalog.find(name)
                .<ResponseEntity<?>>map(script -> {
                    String etag = "\"" + script.sha256() + "\"";
                    if (etag.equals(ifNoneMatch)) {
                        return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
                    }
                    return ResponseEntity.ok()
                            .contentType(JAVASCRIPT)
                            .eTag(etag)
                            .cacheControl(CacheControl.noCache().cachePrivate().mustRevalidate())
                            .body(script.bytes());
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("code", "NOT_FOUND", "message", "no script named " + name)));
    }
}
```

- [ ] **Step 4: Advertise the manifest in the hello reply**

In `BridgeWebSocketHandler`: add a constructor parameter `ScriptCatalog scripts` (import `me.nageshk.whatsapp.scripts.ScriptCatalog`), store it in a field, and change the hello branch to:

```java
            ObjectNode result = mapper.createObjectNode().put("ok", true);
            result.set("scripts", scripts.manifest());
            device.send(Envelope.response(frame.id(), result));
            return;
```

- [ ] **Step 5: Run tests**

Run: `cd whatsapp-backend && ./mvnw -q test -Dtest='ScriptsApiIT,BridgeWebSocketIT'` then `./mvnw -q test`
Expected: all pass. Confirm with `grep -h "Tests run" target/surefire-reports/*ScriptsApiIT.txt` (3 tests).

- [ ] **Step 6: README**

In `whatsapp-backend/README.md` API section, add after the `/api/rpc` example:

```bash
# scripts delivered to the extension (manifest + code; ETag = sha256)
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/scripts
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/scripts/page -o page.js
```

And a paragraph under a new heading `## Page script delivery`: "The extension fetches `page.js` from this server on every connect when the hash in the `hello` reply differs from its cache. Regenerate the resources with `cd whatsapp-web-extension && npm run build` and commit `whatsapp-backend/src/main/resources/wabridge/scripts/`; the server refuses to start if the manifest hash does not match the file."

- [ ] **Step 7: Commit**

```bash
git add server
git commit --no-verify -m "feat(server): serve page script with ETag and advertise it in the hello reply

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Extension pure modules: script cache logic, hello callback, tab readiness

**Files:**
- Create: `whatsapp-web-extension/src/background/scripts.js`, `whatsapp-web-extension/tests/scripts.test.js`
- Modify: `whatsapp-web-extension/src/background/connection.js`, `whatsapp-web-extension/tests/connection.test.js`, `whatsapp-web-extension/src/background/router.js`, `whatsapp-web-extension/tests/router.test.js`

**Interfaces:**
- `scripts.js`: `class ScriptIntegrityError extends Error`; `sha256Hex(text): Promise<string>` (WebCrypto); `ensurePageScript({ manifestEntry, cached, fetchScript }): Promise<{ code, version, sha256, changed } | null>`:
  - no `manifestEntry` → return `cached ? {...cached, changed:false} : null`
  - `cached?.sha256 === manifestEntry.sha256` → `{...cached, changed:false}`
  - else `code = await fetchScript('page')`, verify hash, return `{ code, version: manifestEntry.version, sha256, changed: true }`.
- `Connection` option `onHello(result, error)`; the hello response is not passed to `onFrame`.
- `Router`: `attachTab(tab)` marks not ready; `setTabReady(tab, ready)`; `isTabReady()`; `toTab(message): boolean` posts any message to the attached tab; `fromServer` requires readiness.

- [ ] **Step 1: Write the failing tests**

`whatsapp-web-extension/tests/scripts.test.js`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { ensurePageScript, sha256Hex, ScriptIntegrityError } from '../src/background/scripts.js';

const hash = (s) => createHash('sha256').update(s).digest('hex');
const entry = (code, version = 'v1') => ({ version, sha256: hash(code), size: code.length });

test('sha256Hex uses WebCrypto and matches node crypto', async () => {
    assert.equal(await sha256Hex('hello'), hash('hello'));
});

test('returns null without manifest entry or cache', async () => {
    assert.equal(await ensurePageScript({ manifestEntry: undefined, cached: null, fetchScript: async () => 'x' }), null);
});

test('keeps the cache when the manifest hash is unchanged (no fetch)', async () => {
    let fetched = 0;
    const cached = { code: 'c', version: 'v1', sha256: hash('c') };
    const out = await ensurePageScript({ manifestEntry: entry('c'), cached, fetchScript: async () => { fetched++; return 'c'; } });
    assert.equal(fetched, 0);
    assert.deepEqual(out, { ...cached, changed: false });
});

test('fetches and verifies when the hash changed', async () => {
    const names = [];
    const out = await ensurePageScript({
        manifestEntry: entry('new code', 'v2'),
        cached: { code: 'c', version: 'v1', sha256: hash('c') },
        fetchScript: async (name) => { names.push(name); return 'new code'; },
    });
    assert.deepEqual(names, ['page']);
    assert.deepEqual(out, { code: 'new code', version: 'v2', sha256: hash('new code'), changed: true });
});

test('rejects a download whose hash does not match the manifest', async () => {
    await assert.rejects(
        ensurePageScript({ manifestEntry: entry('expected'), cached: null, fetchScript: async () => 'tampered' }),
        ScriptIntegrityError,
    );
});

test('falls back to the cache when no manifest entry is present', async () => {
    const cached = { code: 'c', version: 'v1', sha256: hash('c') };
    assert.deepEqual(await ensurePageScript({ manifestEntry: null, cached, fetchScript: async () => 'x' }), { ...cached, changed: false });
});
```

Append to `whatsapp-web-extension/tests/connection.test.js`:

```js
test('hello reply is delivered to onHello and not to onFrame', async () => {
    const hellos = [];
    const h = harness({ onHello: (result, error) => hellos.push({ result, error }) });
    await h.conn.start();
    await h.authenticateOk();
    const helloId = h.last().sent[1].id;

    h.last().receive({ type: 'response', id: helloId, result: { ok: true, scripts: { page: { version: 'v1' } } } });
    assert.deepEqual(hellos, [{ result: { ok: true, scripts: { page: { version: 'v1' } } }, error: undefined }]);
    assert.deepEqual(h.frames, []);
});
```

In `whatsapp-web-extension/tests/router.test.js` replace the test `server requests go to the attached tab` with:

```js
test('server requests reach the tab only once it is marked ready', () => {
    const router = new Router();
    const conn = fakeConnection();
    router.setConnection(conn);
    const tab = fakeTab();
    router.attachTab(tab);
    const req = { type: 'request', id: 'r1', method: 'sendMessage', params: {} };

    router.fromServer(req);
    assert.deepEqual(tab.received, []);
    assert.equal(conn.sent[0].error.code, 'WHATSAPP_NOT_READY');

    router.setTabReady(tab, true);
    router.fromServer(req);
    assert.deepEqual(tab.received, [req]);
    assert.equal(router.hasTab(), true);
    assert.equal(router.isTabReady(), true);

    router.setTabReady({}, true); // unknown tab is ignored
    router.detachTab(tab);
    assert.equal(router.isTabReady(), false);
});

test('toTab posts arbitrary control messages to the attached tab', () => {
    const router = new Router();
    assert.equal(router.toTab({ type: 'inject' }), false);
    const tab = fakeTab();
    router.attachTab(tab);
    assert.equal(router.toTab({ type: 'inject', name: 'page' }), true);
    assert.deepEqual(tab.received, [{ type: 'inject', name: 'page' }]);
});
```

and in `detachTab only detaches the same tab; a newer tab replaces the older` add `router.setTabReady(b, true);` right after `router.attachTab(b);`.

- [ ] **Step 2: Run to verify failure**

Run: `cd whatsapp-web-extension && npm test`
Expected: `scripts.test.js` module not found; the hello test fails (`onHello` never called); the router readiness tests fail.

- [ ] **Step 3: Implement scripts.js**

`whatsapp-web-extension/src/background/scripts.js`:

```js
// Decides whether the page script must be (re)downloaded. Pure module: fetch and storage are injected.

export class ScriptIntegrityError extends Error {}

export async function sha256Hex(text) {
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
    return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * @param {object} opts
 * @param {{version:string, sha256:string}|null|undefined} opts.manifestEntry  from the hello reply
 * @param {{code:string, version:string, sha256:string}|null} opts.cached      from chrome.storage
 * @param {(name: string) => Promise<string>} opts.fetchScript                 authenticated download
 * @returns {Promise<{code:string, version:string, sha256:string, changed:boolean}|null>}
 */
export async function ensurePageScript({ manifestEntry, cached, fetchScript }) {
    if (!manifestEntry?.sha256) return cached ? { ...cached, changed: false } : null;
    if (cached?.sha256 === manifestEntry.sha256) return { ...cached, changed: false };

    const code = await fetchScript('page');
    const sha256 = await sha256Hex(code);
    if (sha256 !== manifestEntry.sha256) {
        throw new ScriptIntegrityError(`page script sha256 mismatch: expected ${manifestEntry.sha256}, got ${sha256}`);
    }
    return { code, version: manifestEntry.version, sha256, changed: true };
}
```

- [ ] **Step 4: Add onHello to Connection**

In `connection.js` constructor options add `onHello = () => {}` and `this.onHello = onHello; this.helloId = null;`. Where the hello request is sent (`this.send(request(newId(), 'hello', this.helloParams()));`) replace with:

```js
            this.helloId = newId();
            this.send(request(this.helloId, 'hello', this.helloParams()));
```

In `_onMessage`, right after the `if (frame.type === 'pong') return;` line add:

```js
        if (frame.type === 'response' && frame.id === this.helloId) {
            this.helloId = null;
            this.onHello(frame.result, frame.error);
            return;
        }
```

- [ ] **Step 5: Router readiness and toTab**

In `router.js`: add `this.tabReady = false;` in the constructor; `attachTab(tab)` sets `this.tab = tab; this.tabReady = false;`; `detachTab(tab)` also resets `this.tabReady = false` when it matches. Add:

```js
    setTabReady(tab, ready) {
        if (this.tab === tab) this.tabReady = Boolean(ready);
    }

    isTabReady() {
        return this.tab !== null && this.tabReady;
    }

    /** Posts a non-envelope control message (e.g. inject) to the attached tab. */
    toTab(message) {
        if (!this.tab) return false;
        this.tab.postMessage(message);
        return true;
    }
```

Change `fromServer` to require readiness:

```js
        if (this.tab && this.tabReady) {
            this.tab.postMessage(frame);
            return;
        }
        this._send(errorResponse(frame.id, ErrorCodes.WHATSAPP_NOT_READY,
            this.tab ? 'page script is not running in the WhatsApp tab yet' : 'no WhatsApp Web tab is connected'));
```

- [ ] **Step 6: Run tests and lint**

Run: `cd whatsapp-web-extension && npm test && cd .. && npx --no-install prettier --write extension/src extension/tests && npx --no-install eslint extension`
Expected: all tests pass (previous 40 + 8 new), lint clean.

- [ ] **Step 7: Commit**

```bash
git add extension/src extension/tests
git commit --no-verify -m "feat(extension): page-script cache logic, hello callback and tab readiness gating

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Wire delivery into the worker, relay and popup

**Files:**
- Modify: `whatsapp-web-extension/src/background/storage.js`, `whatsapp-web-extension/src/background/index.js`, `whatsapp-web-extension/src/content/relay.js`, `whatsapp-web-extension/src/popup/popup.js`, `whatsapp-web-extension/README.md`

**Interfaces:**
- `storage.js`: `loadPageScript(): Promise<{code,version,sha256}|null>`, `savePageScript(entry)`.
- Status gains `pageScriptVersion` (string or null) and `pageReady` (boolean).
- Worker↔relay control messages as in Global Constraints.

- [ ] **Step 1: storage.js**

Append:

```js
export async function loadPageScript() {
    const { pageScript } = await area().get('pageScript');
    return pageScript ?? null;
}

export async function savePageScript(entry) {
    await area().set({ pageScript: { code: entry.code, version: entry.version, sha256: entry.sha256 } });
}
```

- [ ] **Step 2: relay.js: handle inject control messages**

Replace the `port.onMessage.addListener((frame) => { window.postMessage(...) })` block with:

```js
    port.onMessage.addListener((msg) => {
        if (msg?.type === 'inject') {
            injectScript(msg);
            return;
        }
        window.postMessage({ source: CONTENT_SOURCE, frame: msg }, WA_ORIGIN);
    });
```

and add above `connectPort`:

```js
// Runs server-delivered code in the page's MAIN world. WhatsApp's CSP forbids inline and eval'd
// scripts but allows `blob:` script sources, so the code is served from an object URL.
function injectScript({ name, version, code }) {
    const url = URL.createObjectURL(new Blob([code], { type: 'text/javascript' }));
    const el = document.createElement('script');
    el.src = url;
    el.async = false;
    el.addEventListener('load', () => {
        el.remove();
        URL.revokeObjectURL(url);
        port?.postMessage({ type: 'injected', name, version });
    });
    el.addEventListener('error', () => {
        el.remove();
        URL.revokeObjectURL(url);
        port?.postMessage({ type: 'inject-failed', name, version, message: 'script element failed to load (CSP?)' });
    });
    (document.head || document.documentElement).appendChild(el);
}
```

- [ ] **Step 3: index.js wiring**

Apply these edits to `whatsapp-web-extension/src/background/index.js`:

1. Imports:
```js
import { loadSettings, saveSettings, setStatus, loadPageScript, savePageScript } from './storage.js';
import { ensurePageScript } from './scripts.js';
```
2. State: after `let lastDetail = '';` add `let lastToken = null;` and `let pageScript = null;`.
3. `login()` unchanged; in `connect()` use `authenticate: async () => { lastToken = await login(settings); return lastToken; },` and add `onHello: (result, error) => handleHello(result, error),`.
4. `publishStatus()` gains `pageScriptVersion: pageScript?.version ?? null, pageReady: router.isTabReady(),`.
5. Add these functions before the popup section:

```js
async function fetchScript(name) {
    const res = await fetch(new URL(`/api/scripts/${name}`, settings.serverUrl), {
        headers: { Authorization: `Bearer ${lastToken}` },
    });
    if (!res.ok) throw new Error(`script download failed: HTTP ${res.status}`);
    return res.text();
}

async function handleHello(result, error) {
    if (error) {
        lastDetail = `hello rejected: ${error.message || error.code}`;
        return publishStatus();
    }
    try {
        const next = await ensurePageScript({
            manifestEntry: result?.scripts?.page,
            cached: pageScript,
            fetchScript,
        });
        if (!next) {
            lastDetail = 'server offers no page script';
            return publishStatus();
        }
        if (next.changed) {
            await savePageScript(next);
            console.info(TAG, 'page script updated to', next.version);
        }
        const fresh = next.changed || !router.isTabReady();
        pageScript = { code: next.code, version: next.version, sha256: next.sha256 };
        if (fresh) injectPage();
    } catch (err) {
        lastDetail = `page script: ${err.message}`;
        console.warn(TAG, 'page script delivery failed', err);
    }
    return publishStatus();
}

function injectPage() {
    if (!pageScript || !router.hasTab()) return;
    router.toTab({ type: 'inject', name: 'page', version: pageScript.version, code: pageScript.code });
}
```

6. In the `onConnect` handler replace the `port.onMessage.addListener((frame) => { trackWaState(frame); router.fromPage(frame); });` with:

```js
    port.onMessage.addListener((msg) => {
        if (msg?.type === 'injected') {
            router.setTabReady(tab, true);
            console.info(TAG, 'page script running', msg.version);
            publishStatus();
            return;
        }
        if (msg?.type === 'inject-failed') {
            lastDetail = `page script: ${msg.message}`;
            publishStatus();
            return;
        }
        if (msg?.type === 'event' && msg.name === Events.READY) router.setTabReady(tab, true);
        trackWaState(msg);
        router.fromPage(msg);
    });
```

and replace the `chrome.scripting.executeScript({ ... files: ['dist/page.js'] })` block with `injectPage();`.

7. Boot: replace the final `loadSettings().then(...)` with:

```js
Promise.all([loadSettings(), loadPageScript()])
    .then(([saved, cachedScript]) => {
        pageScript = cachedScript;
        return saved ? connect(saved) : publishStatus();
    })
    .catch((err) => console.error(TAG, 'boot failed', err));
```

- [ ] **Step 4: popup**

In `render()` after the `WhatsApp tab` line add:

```js
    if (s.pageScriptVersion !== undefined) lines.push(`Page script: ${s.pageScriptVersion ?? 'none'}${s.pageReady ? ' (running)' : ''}`);
```

- [ ] **Step 5: README**

In `whatsapp-web-extension/README.md` replace the "How it works" bullet about `dist/page.js` with: "The page script is **not** in the package. The service worker downloads `page.js` from the server (`GET /api/scripts/page`) whenever the hash announced in the `hello` reply changes, verifies it, caches it in `chrome.storage`, and the content relay injects it via a `blob:` URL `<script>` tag (permitted by WhatsApp's CSP). To ship a page-script change: `npm run build`, commit `whatsapp-backend/src/main/resources/wabridge/scripts/`, redeploy the server. No extension reload needed." Under Build add: "`npm run build` also writes the page script into the server resources; use `npm run build -- --no-sync` to skip."

- [ ] **Step 6: Build, lint, test**

Run:
```bash
cd /Users/nagesh/Projects/WhatsAppWebJS/SCM/whatsapp-web.js
npx --no-install prettier --write extension/src extension/README.md && npx --no-install eslint extension
cd whatsapp-web-extension && npm test && npm run build && ls dist && for f in dist/*.js; do node --check "$f"; done
grep -c "dist/page.js" dist/background.js
```
Expected: lint clean, all tests pass, `dist` contains only `background.js content.js popup.js`, syntax ok, and the grep prints `0`.

- [ ] **Step 7: Commit**

```bash
git add extension server/src/main/resources/wabridge
git commit --no-verify -m "feat(extension): download, verify and inject the page script from the server

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Manual end-to-end verification

- [ ] **Step 1: Fresh server and extension**

Restart the server (IntelliJ) and reload the extension once on `chrome://extensions` (last time it is needed). Popup expected: `Server: connected`, `WhatsApp tab: attached`, `Page script: <12hex> (running)`, `WhatsApp: CONNECTED`. The WhatsApp tab console shows `[wabridge] ready … build <12hex>` with the same version as `curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/scripts`.

- [ ] **Step 2: Send and receive**

`POST /api/messages` as in the server README returns the message DTO; `GET /api/events` shows `message_create` and `message_ack`.

- [ ] **Step 3: Update without touching Chrome**

Edit the `console.info(TAG, 'ready', …)` line in `whatsapp-web-extension/src/page/inject.js` (e.g. add the text `v2`), run `cd whatsapp-web-extension && npm run build`, confirm `git status` shows the two resource files changed, restart the server. Within 30 s (watchdog/reconnect) or after clicking Disconnect/Connect in the popup, the popup shows the new `Page script` version and the tab console shows the new log line with the new build id. No `chrome://extensions` interaction.

- [ ] **Step 4: Tamper check**

Temporarily edit one character in `whatsapp-backend/src/main/resources/wabridge/scripts/page.js` and start the server: it must fail to start with `sha256 mismatch`. Revert the edit (`git checkout -- server/src/main/resources/wabridge/scripts/page.js`).

- [ ] **Step 5: Record**

Append a "Verified on <date>, Chrome <version>, WhatsApp Web <version>" line to `whatsapp-web-extension/README.md` and commit:

```bash
git add extension/README.md
git commit --no-verify -m "docs(extension): record server-delivered page script verification

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## Self-review notes

- Spec coverage: resources + manifest (T1), `ScriptCatalog` fail-fast (T2), endpoints + ETag/304/404 + hello manifest (T3), `ensurePageScript` with integrity error, `onHello`, readiness gating and `toTab` (T4), storage cache, fetch with bearer, inject on hello and on tab attach, relay blob injection with `injected`/`inject-failed`, popup line, README, no page.js in package (T5), manual checks incl. update-without-reload and tamper (T6).
- Type consistency: `ensurePageScript` returns `{code, version, sha256, changed}` and `index.js` stores `{code, version, sha256}`; control message names `inject`/`injected`/`inject-failed` identical in relay and worker; `router.setTabReady/isTabReady/toTab` names match tests and worker; manifest field names `version/sha256/size/builtAt` identical in build, catalog test, and controller test.
