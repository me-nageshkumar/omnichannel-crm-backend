package me.nageshk.omnichannel.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import java.time.Instant;
import me.nageshk.omnichannel.auth.LoginAttemptService;
import me.nageshk.omnichannel.support.TestUsers;
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
class TwoFactorEnrolmentIT {

    @Autowired MockMvc mvc;
    @Autowired TestUsers testUsers;
    @Autowired UserRepository repo;
    @Autowired ObjectMapper mapper;
    @Autowired LoginAttemptService attempts;

    @BeforeEach
    void clean() {
        testUsers.deleteAll();
        attempts.clearOtp("dev");
        attempts.clearLogin("dev");
    }

    static String codeFor(String secret) throws Exception {
        return new DefaultCodeGenerator().generate(secret, Instant.now().getEpochSecond() / 30);
    }

    private ResultActions postAs(String token, String path, String body) throws Exception {
        return mvc.perform(post("/api/users/me/two-factor/" + path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }

    @Test
    void initiateReturnsSecretAndQr() throws Exception {
        String token = testUsers.tokenFor("dev");
        postAs(token, "initiate", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret", hasLength(32)))
                .andExpect(jsonPath("$.qrCodeDataUri", startsWith("data:image/png;base64,")))
                .andExpect(jsonPath("$.issuer").value("Omnichannel CRM"))
                .andExpect(jsonPath("$.username").value("dev"));
    }

    @Test
    void confirmEnablesTwoFactor() throws Exception {
        String token = testUsers.tokenFor("dev");
        String body = postAs(token, "initiate", "{}").andReturn().getResponse().getContentAsString();
        String secret = mapper.readTree(body).get("secret").asText();

        postAs(token, "confirm", "{\"secret\":\"" + secret + "\",\"otp\":\"" + codeFor(secret) + "\"}")
                .andExpect(status().isNoContent());

        assertThat(repo.findByUsername("dev").orElseThrow().getTwoFactor()).isEqualTo(TwoFactor.enabledWith(secret));
        login("dev", "pw").andExpect(status().isOk()).andExpect(jsonPath("$.twoFactorRequired").value(true));
    }

    @Test
    void confirmWithWrongCodeReturns400() throws Exception {
        String token = testUsers.tokenFor("dev");
        postAs(token, "confirm", "{\"secret\":\"" + TestUsers.TEST_SECRET + "\",\"otp\":\"000000\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OTP"));
        assertThat(repo.findByUsername("dev").orElseThrow().getTwoFactor()).isEqualTo(TwoFactor.disabled());
    }

    @Test
    void initiateWhenAlreadyEnabledReturns409() throws Exception {
        User u = testUsers.create("dev", "pw", "WHATSAPP_DEVICE", TestUsers.TEST_SECRET);
        postAs(testUsers.accessToken(u), "initiate", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TWO_FACTOR_STATE"));
    }

    @Test
    void disableWithValidCodeClearsSecret() throws Exception {
        User u = testUsers.create("dev", "pw", "WHATSAPP_DEVICE", TestUsers.TEST_SECRET);
        postAs(testUsers.accessToken(u), "disable", "{\"otp\":\"" + codeFor(TestUsers.TEST_SECRET) + "\"}")
                .andExpect(status().isNoContent());

        assertThat(repo.findByUsername("dev").orElseThrow().getTwoFactor()).isEqualTo(TwoFactor.disabled());
        login("dev", "pw").andExpect(status().isOk()).andExpect(jsonPath("$.token").exists());
    }

    @Test
    void disableWhenAlreadyOffReturns409() throws Exception {
        postAs(testUsers.tokenFor("dev"), "disable", "{\"otp\":\"123456\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TWO_FACTOR_STATE"));
    }

    @Test
    void disableWithWrongCodeReturns400() throws Exception {
        User u = testUsers.create("dev", "pw", "WHATSAPP_DEVICE", TestUsers.TEST_SECRET);
        postAs(testUsers.accessToken(u), "disable", "{\"otp\":\"000000\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OTP"));
        assertThat(repo.findByUsername("dev").orElseThrow().getTwoFactor())
                .isEqualTo(TwoFactor.enabledWith(TestUsers.TEST_SECRET));
    }

    @Test
    void thirdWrongCodeLocksWith429() throws Exception {
        String token = testUsers.tokenFor("dev");
        String wrong = "{\"secret\":\"" + TestUsers.TEST_SECRET + "\",\"otp\":\"000000\"}";
        for (int i = 0; i < 3; i++) {
            postAs(token, "confirm", wrong).andExpect(status().isBadRequest());
        }
        postAs(token, "confirm", wrong)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
    }
}
