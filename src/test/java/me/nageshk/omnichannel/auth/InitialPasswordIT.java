package me.nageshk.omnichannel.auth;

import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class InitialPasswordIT {

    private static final String NEW_PASSWORD = "a-brand-new-password";

    @Autowired MockMvc mvc;
    @Autowired TestUsers testUsers;
    @Autowired UserRepository repo;
    @Autowired ObjectMapper mapper;
    @Autowired LoginAttemptService attempts;
    @Autowired JwtService jwt;

    @BeforeEach
    void clean() {
        testUsers.deleteAll();
        attempts.clearLogin("newbie");
        attempts.clearOtp("newbie");
    }

    private User mustChange(String totpSecret) {
        User u = testUsers.create("newbie", "temporary-pw", "SUPER_ADMIN", totpSecret);
        u.setMustChangePassword(true);
        return repo.save(u);
    }

    private JsonNode login(String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newbie\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private ResultActions initialPassword(String bearer, String newPassword) throws Exception {
        var req = post("/api/auth/initial-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"" + newPassword + "\"}");
        if (bearer != null) req = req.header("Authorization", "Bearer " + bearer);
        return mvc.perform(req);
    }

    private String changeToken() throws Exception {
        return login("temporary-pw").get("passwordChangeToken").asText();
    }

    @Test
    void loginReturnsChangeTokenWhenMustChange() throws Exception {
        mustChange(null);
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newbie\",\"password\":\"temporary-pw\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true))
                .andExpect(jsonPath("$.passwordChangeToken", not(emptyString())))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(jwt.verifyPasswordChangeToken(mapper.readTree(body).get("passwordChangeToken").asText()))
                .contains("newbie");
    }

    @Test
    void otpStepReturnsChangeTokenWhenMustChangeAndTwoFactorOn() throws Exception {
        mustChange(TestUsers.TEST_SECRET);
        JsonNode first = login("temporary-pw");
        assertThat(first.has("twoFactorToken")).isTrue();
        assertThat(first.has("passwordChangeToken")).isFalse();

        mvc.perform(post("/api/auth/verify-otp").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + first.get("twoFactorToken").asText())
                        .content("{\"otp\":\"" + VerifyOtpIT.currentCode() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true))
                .andExpect(jsonPath("$.passwordChangeToken", not(emptyString())))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void changeTokenIsNotAnAccessToken() throws Exception {
        mustChange(null);
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + changeToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void initialPasswordIssuesAccessTokenAndClearsFlag() throws Exception {
        mustChange(null);
        String body = initialPassword(changeToken(), NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", not(emptyString())))
                .andExpect(jsonPath("$.expiresAt", not(emptyString())))
                .andReturn().getResponse().getContentAsString();
        JsonNode json = mapper.readTree(body);
        assertThat(Instant.parse(json.get("expiresAt").asText())).isAfter(Instant.now());

        User saved = repo.findByUsername("newbie").orElseThrow();
        assertThat(saved.isMustChangePassword()).isFalse();
        assertThat(saved.getTokenVersion()).isEqualTo(1L);
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + json.get("token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    void oldPasswordNoLongerWorks() throws Exception {
        mustChange(null);
        initialPassword(changeToken(), NEW_PASSWORD).andExpect(status().isOk());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newbie\",\"password\":\"temporary-pw\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(login(NEW_PASSWORD).has("token")).isTrue();
    }

    @Test
    void shortPasswordIs400WeakPassword() throws Exception {
        mustChange(null);
        initialPassword(changeToken(), "123456789")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
        assertThat(repo.findByUsername("newbie").orElseThrow().isMustChangePassword()).isTrue();
    }

    @Test
    void sameAsTemporaryIs400() throws Exception {
        mustChange(null);
        initialPassword(changeToken(), "temporary-pw")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
    }

    @Test
    void expiredOrAccessTokenIs401InvalidToken() throws Exception {
        User u = mustChange(null);
        initialPassword(testUsers.accessToken(u), NEW_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        initialPassword("garbage", NEW_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        initialPassword(null, NEW_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void changeTokenIsSingleUse() throws Exception {
        mustChange(null);
        String token = changeToken();
        initialPassword(token, NEW_PASSWORD).andExpect(status().isOk());
        initialPassword(token, "another-new-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void inactiveUserIsRefused() throws Exception {
        User u = mustChange(null);
        String token = changeToken();
        u.setActive(false);
        repo.save(u);
        initialPassword(token, NEW_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void deviceUserLoginUnchanged() throws Exception {
        testUsers.create("newbie", "pw", "WHATSAPP_DEVICE", null);
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newbie\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = mapper.readTree(body);
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("token", "expiresAt");
    }
}
