package me.nageshk.omnichannel.auth;

import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import java.time.Instant;
import me.nageshk.omnichannel.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class VerifyOtpIT {

    @Autowired MockMvc mvc;
    @Autowired TestUsers testUsers;
    @Autowired ObjectMapper mapper;
    @Autowired LoginAttemptService attempts;

    @BeforeEach
    void seed() {
        testUsers.deleteAll();
        attempts.clearOtp("admin");
        attempts.clearLogin("admin");
        testUsers.create("admin", "pw", "SUPER_ADMIN", TestUsers.TEST_SECRET);
    }

    static String currentCode() throws Exception {
        return new DefaultCodeGenerator().generate(TestUsers.TEST_SECRET, Instant.now().getEpochSecond() / 30);
    }

    private String pendingToken() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = mapper.readTree(body);
        return json.get("twoFactorToken").asString();
    }

    private ResultActions verify(String bearer, String otp) throws Exception {
        var req = post("/api/auth/verify-otp").contentType(MediaType.APPLICATION_JSON)
                .content("{\"otp\":\"" + otp + "\"}");
        if (bearer != null) req = req.header("Authorization", "Bearer " + bearer);
        return mvc.perform(req);
    }

    @Test
    void validOtpReturnsAccessToken() throws Exception {
        verify(pendingToken(), currentCode())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", not(emptyString())))
                .andExpect(jsonPath("$.expiresAt", not(emptyString())));
    }

    @Test
    void wrongOtpReturns401InvalidOtp() throws Exception {
        verify(pendingToken(), "000000")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_otp"));
    }

    @Test
    void fourthWrongOtpReturns429() throws Exception {
        String pending = pendingToken();
        for (int i = 0; i < 3; i++) verify(pending, "000000").andExpect(status().isUnauthorized());
        verify(pending, "000000")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("too_many_attempts"));
        verify(pending, currentCode()).andExpect(status().isTooManyRequests());
    }

    @Test
    void verifyOtpWithoutBearerReturns401InvalidToken() throws Exception {
        verify(null, currentCode())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_token"));
    }

    @Test
    void verifyOtpWithAccessTokenAsBearerReturns401() throws Exception {
        String access = testUsers.tokenFor("dev");
        verify(access, currentCode())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_token"));
    }

    @Test
    void pendingTokenIsReusableWithinWindow() throws Exception {
        String pending = pendingToken();
        verify(pending, currentCode()).andExpect(status().isOk());
        verify(pending, currentCode()).andExpect(status().isOk());
    }
}
