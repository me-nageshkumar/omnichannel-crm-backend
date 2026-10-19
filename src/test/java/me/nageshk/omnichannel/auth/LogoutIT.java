package me.nageshk.omnichannel.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import me.nageshk.omnichannel.config.OmnichannelProperties;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class LogoutIT {

    @Autowired MockMvc mvc;
    @Autowired TestUsers testUsers;
    @Autowired RevokedTokenRepository revoked;
    @Autowired JwtService jwt;
    @Autowired OmnichannelProperties props;
    @Autowired MongoTemplate mongo;
    @Autowired LoginAttemptService attempts;

    private User user;

    @BeforeEach
    void seed() {
        testUsers.deleteAll();
        revoked.deleteAll();
        attempts.clearLogin("dev");
        user = testUsers.create("dev", "pw", UserRole.USER, null);
    }

    private ResultActions logout(String token) throws Exception {
        return mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token));
    }

    private ResultActions getStatus(String token) throws Exception {
        return mvc.perform(get("/api/status").header("Authorization", "Bearer " + token));
    }

    @Test
    void logoutRevokesThatToken() throws Exception {
        String a = testUsers.accessToken(user);
        logout(a).andExpect(status().isNoContent());
        getStatus(a).andExpect(status().isUnauthorized());
    }

    @Test
    void otherTokensOfSameUserKeepWorking() throws Exception {
        String a = testUsers.accessToken(user);
        String b = testUsers.accessToken(user);
        logout(a).andExpect(status().isNoContent());
        getStatus(b).andExpect(status().isOk());
    }

    @Test
    void revocationEntryExpiresWithToken() throws Exception {
        String a = testUsers.accessToken(user);
        JwtService.AccessClaims claims = jwt.verifyAccessToken(a).orElseThrow();
        logout(a).andExpect(status().isNoContent());

        RevokedToken entry = revoked.findById(claims.jti()).orElseThrow();
        assertThat(entry.getUsername()).isEqualTo("dev");
        assertThat(entry.getExpiresAt()).isEqualTo(claims.expiresAt());
        assertThat(mongo.indexOps(RevokedToken.class).getIndexInfo())
                .filteredOn(i -> i.isIndexForFields(java.util.List.of("expiresAt")))
                .singleElement()
                .extracting(IndexInfo::getExpireAfter)
                .isEqualTo(java.util.Optional.of(Duration.ZERO));
    }

    @Test
    void logoutTwiceIsUnauthorized() throws Exception {
        String a = testUsers.accessToken(user);
        logout(a).andExpect(status().isNoContent());
        logout(a).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithPendingTokenIsUnauthorized() throws Exception {
        logout(jwt.issuePendingToken("dev")).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithoutJtiStillAccepted() throws Exception {
        Instant now = Instant.now();
        String legacy = Jwts.builder()
                .subject("dev")
                .claim(JwtService.CLAIM_USER_ID, user.getId())
                .claim(JwtService.CLAIM_ROLE, "USER")
                .claim(JwtService.CLAIM_TOKEN_VERSION, 0L)
                .claim(JwtService.CLAIM_TWO_FACTOR_ENABLED, false)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofHours(1))))
                .signWith(Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8)))
                .compact();
        getStatus(legacy).andExpect(status().isOk());
    }

    @Test
    void loginStillWorks() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dev\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());
    }
}
