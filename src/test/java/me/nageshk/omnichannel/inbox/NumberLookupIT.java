package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import me.nageshk.omnichannel.access.Role;
import me.nageshk.omnichannel.access.RoleLevel;
import me.nageshk.omnichannel.access.RoleRepository;
import me.nageshk.omnichannel.rpc.Envelope;
import me.nageshk.omnichannel.support.FakeDevice;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.WorkspaceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The cached "is this number on WhatsApp" lookup: {@code GET /phone-numbers/{pn}/number-lookup} through a {@link FakeDevice}. */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "omnichannel.rpc.timeout=2s")
class NumberLookupIT extends InboxApiTestBase {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired RoleRepository roles;
    @Autowired WaNumberLookupRepository lookups;

    /** What the device saw and what the console got back. */
    record Exchange(ResponseEntity<JsonNode> response, Envelope request) {
    }

    @BeforeEach
    void clearLookups() {
        lookups.deleteAll();
    }

    private String lookupUrl() {
        return "http://localhost:" + port + inbox() + "/phone-numbers/" + number.getId() + "/number-lookup";
    }

    /** The URL is sent as given (a {@link URI}), so the caller's percent-escapes are not encoded twice. */
    private ResponseEntity<JsonNode> getAt(String token, String url) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
    }

    /** {@code phone} goes into the query string as given, so a {@code +} must come URL-encoded. */
    private ResponseEntity<JsonNode> get(String token, String phone, boolean force) {
        return getAt(token, lookupUrl() + "?phone=" + phone + (force ? "&force=true" : ""));
    }

    private ObjectNode ready() {
        return mapper.createObjectNode().put("pushname", "N").put("wid", number.getNumber() + "@c.us");
    }

    private ObjectNode lookupAnswer(String phone, String lid) {
        ObjectNode answer = mapper.createObjectNode().put("phone", phone);
        if (lid == null) answer.putNull("lid");
        else answer.put("lid", lid);
        return answer;
    }

    private void seed(String phone, boolean registered, String lid, Duration age) {
        WaNumberLookup row = new WaNumberLookup();
        row.setWorkspaceId(brand.getId());
        row.setPhoneNumberId(number.getId());
        row.setPhone(phone);
        row.setRegistered(registered);
        row.setWaPhone(registered ? phone + "@c.us" : null);
        row.setLid(lid);
        row.setCheckedAt(Instant.now().minus(age));
        lookups.save(row);
    }

    /** Looks up with a connected device and answers the {@code getNumberId} request it receives. */
    private Exchange viaDevice(String token, String phone, boolean force, Function<Envelope, Envelope> answer)
            throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);
            CompletableFuture<ResponseEntity<JsonNode>> http = CompletableFuture.supplyAsync(
                    () -> get(token, phone, force));
            Envelope req = device.next();
            assertThat(req.method()).isEqualTo("getNumberId");
            device.send(answer.apply(req));
            return new Exchange(http.get(5, TimeUnit.SECONDS), req);
        }
    }

    /** A request with a connected, ready device that must not be asked anything. */
    private ResponseEntity<JsonNode> getUntouched(String token, String phone, boolean force) throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);
            ResponseEntity<JsonNode> res = get(token, phone, force);
            assertThat(device.poll(Duration.ofMillis(300))).isEmpty();
            return res;
        }
    }

    @Test
    void registeredNumberWithLidIsCached() throws Exception {
        Exchange ex = viaDevice(agentToken, "919811111111", false,
                req -> Envelope.response(req.id(), lookupAnswer("919811111111@c.us", "103238296682612@lid")));
        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ex.request().params().get("phone").asString()).isEqualTo("919811111111");
        JsonNode body = ex.response().getBody();
        assertThat(body.get("registered").asBoolean()).isTrue();
        assertThat(body.get("remoteId").asString()).isEqualTo("103238296682612@lid");
        assertThat(body.get("cached").asBoolean()).isFalse();
        WaNumberLookup row = lookups.findByWorkspaceIdAndPhone(brand.getId(), "919811111111").orElseThrow();
        assertThat(row.getLid()).isEqualTo("103238296682612@lid");
        assertThat(row.getWaPhone()).isEqualTo("919811111111@c.us");
        assertThat(row.getPhoneNumberId()).isEqualTo(number.getId());
    }

    @Test
    void registeredWithoutLidReportsTheCusId() throws Exception {
        Exchange ex = viaDevice(agentToken, "919811111111", false,
                req -> Envelope.response(req.id(), lookupAnswer("919811111111@c.us", null)));
        assertThat(ex.response().getBody().get("remoteId").asString()).isEqualTo("919811111111@c.us");
    }

    @Test
    void unregisteredNumberIsCached() throws Exception {
        Exchange ex = viaDevice(agentToken, "919811111111", false,
                req -> Envelope.response(req.id(), mapper.nullNode()));
        JsonNode body = ex.response().getBody();
        assertThat(body.get("registered").asBoolean()).isFalse();
        assertThat(body.get("remoteId").isNull()).isTrue();
        assertThat(lookups.findByWorkspaceIdAndPhone(brand.getId(), "919811111111").orElseThrow().isRegistered())
                .isFalse();
    }

    @Test
    void freshCacheHitSkipsTheDevice() throws Exception {
        seed("919811111111", true, "103238296682612@lid", Duration.ofDays(29));
        ResponseEntity<JsonNode> res = getUntouched(agentToken, "919811111111", false);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("cached").asBoolean()).isTrue();
        assertThat(res.getBody().get("remoteId").asString()).isEqualTo("103238296682612@lid");
    }

    @Test
    void cacheHitNeedsNoDevice() throws Exception {
        seed("919811111111", true, "103238296682612@lid", Duration.ofDays(1));
        ResponseEntity<JsonNode> res = get(agentToken, "919811111111", false); // no device connected at all
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void staleRegisteredAnswerIsReQueried() throws Exception {
        seed("919811111111", true, "old@lid", Duration.ofDays(31));
        Exchange ex = viaDevice(agentToken, "919811111111", false,
                req -> Envelope.response(req.id(), lookupAnswer("919811111111@c.us", "new@lid")));
        assertThat(ex.response().getBody().get("remoteId").asString()).isEqualTo("new@lid");
        assertThat(lookups.count()).isEqualTo(1);
    }

    @Test
    void staleUnregisteredAnswerIsReQueriedAfterAWeek() throws Exception {
        seed("919811111111", false, null, Duration.ofDays(8));
        Exchange ex = viaDevice(agentToken, "919811111111", false,
                req -> Envelope.response(req.id(), lookupAnswer("919811111111@c.us", null)));
        assertThat(ex.response().getBody().get("registered").asBoolean()).isTrue();
    }

    @Test
    void forceReQueriesAFreshAnswer() throws Exception {
        seed("919811111111", false, null, Duration.ofHours(1));
        Exchange ex = viaDevice(agentToken, "919811111111", true,
                req -> Envelope.response(req.id(), lookupAnswer("919811111111@c.us", null)));
        assertThat(ex.response().getBody().get("registered").asBoolean()).isTrue();
        assertThat(ex.response().getBody().get("cached").asBoolean()).isFalse();
    }

    @Test
    void deviceErrorIs502AndNothingIsCached() throws Exception {
        Exchange ex = viaDevice(agentToken, "919811111111", false,
                req -> Envelope.errorResponse(req.id(), "PAGE_ERROR", "boom"));
        assertThat(ex.response().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(ex.response().getBody().get("code").asString()).isEqualTo("LOOKUP_FAILED");
        assertThat(ex.response().getBody().get("message").asString())
                .isEqualTo("Could not check this number on WhatsApp, try again");
        assertThat(lookups.count()).isZero();
    }

    @Test
    void silentDeviceIs502AfterTheTimeout() throws Exception {
        // the device receives the request and never answers; omnichannel.rpc.timeout=2s
        try (FakeDevice device = FakeDevice.connectAndAuth(port, testDevices.redeem(number).deviceKey())) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);
            CompletableFuture<ResponseEntity<JsonNode>> http = CompletableFuture.supplyAsync(
                    () -> get(agentToken, "919811111111", false));
            assertThat(device.next().method()).isEqualTo("getNumberId");
            ResponseEntity<JsonNode> res = http.get(10, TimeUnit.SECONDS);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(res.getBody().get("code").asString()).isEqualTo("LOOKUP_FAILED");
        }
    }

    @Test
    void noDeviceIs409() throws Exception {
        ResponseEntity<JsonNode> res = get(agentToken, "919811111111", false);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("NO_DEVICE");
    }

    @Test
    void badPhoneIs400() throws Exception {
        ResponseEntity<JsonNode> res = get(agentToken, "12", false);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("fields").get("phone").asString())
                .isEqualTo("must be 8 to 15 digits with the country code, e.g. 919812345678");
    }

    @Test
    void formattedPhoneIsNormalisedAsTheCacheKey() throws Exception {
        seed("919811111111", true, "x@lid", Duration.ofDays(1));
        ResponseEntity<JsonNode> res = get(agentToken, "%2B91%2098111-11111", false);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("phone").asString()).isEqualTo("919811111111");
    }

    @Test
    void numberOfAnotherBrandIs404EvenWhenCached() throws Exception {
        seed("919811111111", true, "x@lid", Duration.ofDays(1));
        ResponseEntity<JsonNode> res = getAt(agentToken, "http://localhost:" + port + inbox() + "/phone-numbers/"
                + otherNumber.getId() + "/number-lookup?phone=919811111111");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void requiresInboxSend() throws Exception {
        Role viewOnly = new Role();
        viewOnly.setName("INBOX_VIEWER");
        viewOnly.setLevel(RoleLevel.BRAND);
        viewOnly.setAuthorities(Set.of("inbox:view"));
        viewOnly = roles.save(viewOnly);
        User viewer = testUsers.createInCustomer(acme.getId(), "viewer", null,
                List.of(new WorkspaceRole(brand.getId(), viewOnly.getId())));
        ResponseEntity<JsonNode> res = get(testUsers.accessToken(viewer), "919811111111", false);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
