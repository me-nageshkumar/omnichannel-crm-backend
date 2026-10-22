package me.nageshk.omnichannel.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.support.MutableClock;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.user.WorkspaceRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class DeviceConnectIT {

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-20T10:00:00Z"));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestUsers testUsers;
    @Autowired PhoneNumberRepository numbers;
    @Autowired DeviceKeys keys;
    @Autowired MutableClock clock;
    @Autowired Clock injected;
    @Autowired ConnectAttemptLimiter limiter;

    Customer acme;
    Workspace brandA;
    String wmToken;
    String bossToken;
    String id;

    @BeforeEach
    void setUp() throws Exception {
        testUsers.deleteAll();
        limiter.clearAll();
        acme = testUsers.customer("Acme");
        brandA = testUsers.workspace(acme.getId(), "Brand A");
        bossToken = testUsers.accessToken(
                testUsers.createInCustomer(acme.getId(), "boss", BuiltInRoles.CUSTOMER_ADMIN, List.of()));
        wmToken = testUsers.accessToken(testUsers.createInCustomer(acme.getId(), "wm", null,
                List.of(new WorkspaceRole(brandA.getId(), testUsers.roleId(BuiltInRoles.WORKSPACE_MANAGER)))));
        id = json(send(post(base()), wmToken, Map.of("number", "919928088743"))
                .andExpect(status().isCreated())).get("id").asString();
    }

    @AfterEach
    void clean() {
        testUsers.deleteAll();
        limiter.clearAll();
    }

    private String base() {
        return "/api/workspaces/" + brandA.getId() + "/phone-numbers";
    }

    private ResultActions send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(req);
    }

    private JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String code() throws Exception {
        return json(send(post(base() + "/" + id + "/connect-code"), wmToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.matchesPattern(
                        "^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}$")))
                .andExpect(jsonPath("$.expiresAt").value(clock.instant().plus(Duration.ofMinutes(15)).toString()))).get("code").asString();
    }

    private ResultActions connect(String code, String ip) throws Exception {
        return mvc.perform(post("/api/devices/connect").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("code", code)))
                .with(r -> { r.setRemoteAddr(ip); return r; }));
    }

    @Test
    void usesTheTestClock() {
        assertThat(injected).isSameAs(clock);
    }

    @Test
    void codeRedeemsForKey() throws Exception {
        JsonNode body = json(connect(code(), "10.0.0.1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumberId").value(id))
                .andExpect(jsonPath("$.number").value("919928088743"))
                .andExpect(jsonPath("$.brandName").value("Brand A"))
                .andExpect(jsonPath("$.deviceKeyId").value(org.hamcrest.Matchers.matchesPattern("^[0-9a-f]{8}$"))));
        String key = body.get("deviceKey").asString();
        assertThat(key).startsWith("dk_").hasSizeGreaterThan(40);
        assertThat(keys.authenticate(key)).hasValueSatisfying(p -> {
            assertThat(p.phoneNumberId()).isEqualTo(id);
            assertThat(p.workspaceId()).isEqualTo(brandA.getId());
            assertThat(p.customerId()).isEqualTo(acme.getId());
        });
        PhoneNumber stored = numbers.findById(id).orElseThrow();
        assertThat(stored.getDeviceKeyHash()).isNotEqualTo(key).hasSize(64);
        send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base()), wmToken, null)
                .andExpect(jsonPath("$[0].hasDevice").value(true));
    }

    @Test
    void codeIsSingleUse() throws Exception {
        String code = code();
        connect(code, "10.0.0.1").andExpect(status().isOk());
        connect(code, "10.0.0.1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CONNECT_CODE"))
                .andExpect(jsonPath("$.message").value("Invalid or expired connect code."));
    }

    @Test
    void codeExpiresAfter15Minutes() throws Exception {
        String code = code();
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        connect(code, "10.0.0.1").andExpect(status().isBadRequest());
    }

    @Test
    void newCodeInvalidatesOldCode() throws Exception {
        String first = code();
        String second = code();
        connect(first, "10.0.0.1").andExpect(status().isBadRequest());
        connect(second, "10.0.0.1").andExpect(status().isOk());
    }

    @Test
    void codeAcceptsLowercaseWithoutDash() throws Exception {
        connect(code().replace("-", "").toLowerCase(), "10.0.0.1").andExpect(status().isOk());
    }

    @Test
    void wrongCodeIs400() throws Exception {
        connect("AAAA-AAAA", "10.0.0.1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CONNECT_CODE"));
        connect("", "10.0.0.1").andExpect(status().isBadRequest());
    }

    @Test
    void connectIsRateLimitedPerIp() throws Exception {
        String good = code();
        for (int i = 0; i < 10; i++) connect("AAAA-AAAA", "10.0.0.9").andExpect(status().isBadRequest());
        connect(good, "10.0.0.9").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
        connect(good, "10.0.0.10").andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(16));
        connect("AAAA-AAAA", "10.0.0.9").andExpect(status().isBadRequest());
    }

    @Test
    void disabledNumberCannotGetCode() throws Exception {
        send(post(base() + "/" + id + "/disable"), wmToken, null).andExpect(status().isOk());
        send(post(base() + "/" + id + "/connect-code"), wmToken, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DISABLED"));
    }

    @Test
    void disabledAfterCodeRefusesRedeem() throws Exception {
        String code = code();
        send(post(base() + "/" + id + "/disable"), wmToken, null).andExpect(status().isOk());
        connect(code, "10.0.0.1").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DISABLED"));
    }

    @Test
    void redeemReplacesOldKey() throws Exception {
        String first = json(connect(code(), "10.0.0.1")).get("deviceKey").asString();
        String second = json(connect(code(), "10.0.0.1")).get("deviceKey").asString();
        assertThat(keys.authenticate(first)).isEmpty();
        assertThat(keys.authenticate(second)).isPresent();
    }

    @Test
    void revokeDeviceClearsKey() throws Exception {
        String key = json(connect(code(), "10.0.0.1")).get("deviceKey").asString();
        send(post(base() + "/" + id + "/revoke-device"), wmToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasDevice").value(false));
        assertThat(keys.authenticate(key)).isEmpty();
    }

    @Test
    void keyRefusedWhileBrandDisabledAndWorksAfterEnable() throws Exception {
        String key = json(connect(code(), "10.0.0.1")).get("deviceKey").asString();
        send(post("/api/workspaces/" + brandA.getId() + "/disable"), bossToken, null).andExpect(status().isOk());
        assertThat(keys.authenticate(key)).isEmpty();
        send(post("/api/workspaces/" + brandA.getId() + "/enable"), bossToken, null).andExpect(status().isOk());
        assertThat(keys.authenticate(key)).isPresent();
    }

    @Test
    void agentCannotGetCode() throws Exception {
        String leadToken = testUsers.accessToken(testUsers.createInCustomer(acme.getId(), "lead", null,
                List.of(new WorkspaceRole(brandA.getId(), testUsers.roleId(BuiltInRoles.AGENT)))));
        send(post(base() + "/" + id + "/connect-code"), leadToken, null).andExpect(status().isForbidden());
    }
}
