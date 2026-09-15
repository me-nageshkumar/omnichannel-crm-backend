# wabridge server

Spring Boot backend for the WhatsApp Web Chrome extension bridge. See
`docs/superpowers/specs/2026-09-15-chrome-extension-bridge-design.md`.

## Run

```bash
./mvnw spring-boot:run
```

Default dev user: `demo` / `demo123` (see `src/main/resources/application.yml`). Set
`WABRIDGE_JWT_SECRET` to a random string of at least 32 bytes outside development.

## API

```bash
# login
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo123"}' | jq -r .token)

# status of this user's extension
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/status

# send a text message
curl -s -X POST localhost:8080/api/messages \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"chatId":"919999999999@c.us","content":"hello from server"}'

# recent events (oldest first); poll with after=<last seq>
curl -s -H "Authorization: Bearer $TOKEN" 'localhost:8080/api/events?limit=20'

# call any page method directly (diagnostic / generic pass-through)
curl -s -X POST localhost:8080/api/rpc -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"method":"getState"}'
```

Error codes: `NO_DEVICE` / `WHATSAPP_NOT_READY` (409), `CHAT_NOT_FOUND` / `PAGE_ERROR` /
`VALIDATION` (400), `DEVICE_DISCONNECTED` / `SEND_FAILED` (502), `TIMEOUT` (504).

## WebSocket protocol (`/ws`)

First frame must be `{"type":"auth","token":"<jwt>"}` within 5 s, else close 4401. A newer
connection for the same user replaces the older one (closed with 4409). Frames:
`request{id,method,params}`, `response{id,result|error{code,message}}`, `event{name,payload}`,
`ping`/`pong`. Incoming events are logged by `LoggingEventListener`.
