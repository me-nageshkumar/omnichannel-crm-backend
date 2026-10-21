# Number Lookup (extension) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `getNumberId` page method that tells the backend whether a phone number is on WhatsApp, and under which ids.

**Architecture:** One more entry in `createMethods` (`src/page/methods.js`), mirroring whatsapp-web.js `Client.getNumberId` for the existence query and `WWebJS.enforceLidAndPnRetrieval` for the lid. Tested like `downloadMedia` with a stubbed `globalThis.window`.

**Tech Stack:** Plain ES modules, `node --test`, eslint + prettier.

**Spec:** `omnichannel-crm-backend/docs/superpowers/specs/2026-10-21-number-lookup-design.md` §2. Repo: `whatsapp-web-extension`.

## Global Constraints

- Method name `getNumberId`; params `{ phone }`; result `{ phone, lid } | null` exactly as §2.
- Error codes come from `ErrorCodes` in `src/shared/protocol.js`: `WHATSAPP_NOT_READY` when `window.WWebJS` is undefined (same check and message as `downloadMedia`: `WWebJS helpers are not loaded yet`), `PAGE_ERROR` with message `phone must be a non-empty string` for a missing or non-string phone.
- `npm test` and `npm run lint` must pass (prettier formatting: 4-space indent, single quotes, trailing commas, as the file already is).
- Commit messages end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## Review Focus

- A registered number whose lid WhatsApp does not know locally must still report registered, with `lid: null` (Task 1 test `getNumberId → lid null when WhatsApp has none`).
- `queryWidExists` resolving to an object without `wid` must count as not registered, not as a crash (Task 1 test `getNumberId → null for an unregistered number`).
- `getCurrentLid` throwing must not turn a registered answer into an error: catch and report `lid: null` (Task 1 test `getNumberId → lid null when getCurrentLid throws`).

---

### Task 1: `getNumberId` page method

**Files:**
- Modify: `src/page/methods.js` (add after `sendMessage`, before `downloadMedia`)
- Test: `tests/methods.test.js`

**Interfaces:**
- Produces: page method `getNumberId({ phone: string }) => Promise<{ phone: string, lid: string | null } | null>`.

- [ ] **Step 1: Write the failing tests** in `tests/methods.test.js`. A helper builds the stubbed window:

```js
const getNumberId = () =>
    createMethods({ getInfo: () => null }).get('getNumberId');

/** A window whose WA modules answer the existence query and the lid lookup as given. */
const fakeWindow = ({ exists, lid, lidThrows = false }) => {
    const created = [];
    return {
        created,
        window: {
            WWebJS: {},
            require: (name) => {
                if (name === 'WAWebWidFactory')
                    return {
                        createWid: (id) => {
                            const wid = { _serialized: id };
                            created.push(id);
                            return wid;
                        },
                    };
                if (name === 'WAWebQueryExistsJob')
                    return {
                        queryWidExists: async (wid) =>
                            exists ? { wid } : { wid: undefined },
                    };
                if (name === 'WAWebApiContact')
                    return {
                        getCurrentLid: () => {
                            if (lidThrows) throw new Error('no lid');
                            return lid ? { _serialized: lid } : null;
                        },
                    };
                throw new Error(`unexpected module ${name}`);
            },
        },
    };
};
```

Tests:

```js
test('getNumberId → phone and lid for a registered number', async () => {
    const fake = fakeWindow({ exists: true, lid: '103238296682612@lid' });
    globalThis.window = fake.window;
    const result = await getNumberId()({ phone: '919811111111' });
    assert.deepEqual(result, { phone: '919811111111@c.us', lid: '103238296682612@lid' });
    assert.deepEqual(fake.created, ['919811111111@c.us']);
});

test('getNumberId → lid null when WhatsApp has none', async () => {
    globalThis.window = fakeWindow({ exists: true, lid: null }).window;
    assert.deepEqual(await getNumberId()({ phone: '919811111111' }), { phone: '919811111111@c.us', lid: null });
});

test('getNumberId → lid null when getCurrentLid throws', async () => {
    globalThis.window = fakeWindow({ exists: true, lidThrows: true }).window;
    assert.deepEqual(await getNumberId()({ phone: '919811111111' }), { phone: '919811111111@c.us', lid: null });
});

test('getNumberId → null for an unregistered number', async () => {
    globalThis.window = fakeWindow({ exists: false }).window;
    assert.equal(await getNumberId()({ phone: '919811111111' }), null);
});

test('getNumberId → WHATSAPP_NOT_READY without WWebJS', async () => {
    globalThis.window = {};
    await assert.rejects(getNumberId()({ phone: '919811111111' }), (e) => e.code === 'WHATSAPP_NOT_READY');
});

test('getNumberId → PAGE_ERROR without a phone', async () => {
    globalThis.window = fakeWindow({ exists: true }).window;
    await assert.rejects(getNumberId()({}), (e) => e.code === 'PAGE_ERROR' && e.message === 'phone must be a non-empty string');
});
```

(Format with prettier; the long lines above wrap.)

- [ ] **Step 2: Run to verify it fails**

Run: `npm test -- tests/methods.test.js`
Expected: the six new tests fail (`getNumberId()` is undefined → TypeError).

- [ ] **Step 3: Implement** `methods.set('getNumberId', async ({ phone } = {}) => …)` in `src/page/methods.js`: not-ready check; phone check; `const wid = window.require('WAWebWidFactory').createWid(phone + '@c.us')` (append only when the string does not already end with `@c.us`, as whatsapp-web.js does); `const result = await window.require('WAWebQueryExistsJob').queryWidExists(wid)`; `if (!result || result.wid === undefined) return null;`; lid via `window.require('WAWebApiContact').getCurrentLid(result.wid)` inside try/catch, `?._serialized ?? null`; return `{ phone: result.wid._serialized, lid }`. Add a comment naming the two whatsapp-web.js sources (`Client.js getNumberId`, `Utils.js enforceLidAndPnRetrieval`).

- [ ] **Step 4: Run to verify it passes**

Run: `npm test && npm run lint`
Expected: all tests pass, lint clean.

- [ ] **Step 5: Commit**

```bash
git add src/page/methods.js tests/methods.test.js
git commit -m "feat(page): getNumberId reports whether a number is on WhatsApp"
```
