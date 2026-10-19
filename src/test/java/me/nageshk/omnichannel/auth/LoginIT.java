package me.nageshk.omnichannel.auth;

import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRepository;
import me.nageshk.omnichannel.user.UserRole;
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
class LoginIT {

    @Autowired MockMvc mvc;
    @Autowired TestUsers testUsers;
    @Autowired UserRepository repo;

    @BeforeEach
    void clean() {
        testUsers.deleteAll();
    }

    private ResultActions login(String username, String password) throws Exception {
        String body = password == null
                ? "{\"username\":\"" + username + "\"}"
                : "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void loginWithoutTwoFactorReturnsAccessToken() throws Exception {
        testUsers.create("dev", "pw", UserRole.USER, null);
        login("dev", "pw")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", not(emptyString())))
                .andExpect(jsonPath("$.expiresAt", not(emptyString())))
                .andExpect(jsonPath("$.twoFactorRequired").doesNotExist());
    }

    @Test
    void loginWithTwoFactorReturnsPendingToken() throws Exception {
        testUsers.create("admin", "pw", UserRole.SUPER_ADMIN, TestUsers.TEST_SECRET);
        login("admin", "pw")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.twoFactorRequired").value(true))
                .andExpect(jsonPath("$.twoFactorToken", not(emptyString())))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void wrongPasswordReturns401InvalidCredentials() throws Exception {
        testUsers.create("dev", "pw", UserRole.USER, null);
        login("dev", "nope")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"));
    }

    @Test
    void unknownUsernameReturns401InvalidCredentials() throws Exception {
        login("ghost", "pw")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"));
    }

    @Test
    void inactiveUserReturns401AccountInactive() throws Exception {
        User u = testUsers.create("old", "pw", UserRole.USER, null);
        u.setActive(false);
        repo.save(u);
        login("old", "pw")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("account_inactive"));
    }

    @Test
    void inactiveUserWithWrongPasswordReturnsInvalidCredentials() throws Exception {
        // The inactive state must not be revealed to someone who does not know the password.
        User u = testUsers.create("old", "pw", UserRole.USER, null);
        u.setActive(false);
        repo.save(u);
        login("old", "wrong")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"));
    }

    @Test
    void missingPasswordReturns401() throws Exception {
        testUsers.create("dev", "pw", UserRole.USER, null);
        login("dev", null).andExpect(status().isUnauthorized());
    }

    @Test
    void sixthFailureReturns429() throws Exception {
        testUsers.create("locked", "pw", UserRole.USER, null);
        for (int i = 0; i < 5; i++) login("locked", "bad").andExpect(status().isUnauthorized());
        login("locked", "bad")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("too_many_attempts"))
                .andExpect(jsonPath("$.retryAfterSeconds", greaterThan(0)));
        login("locked", "pw").andExpect(status().isTooManyRequests());
    }

    @Test
    void protectedEndpointWithoutTokenReturns401() throws Exception {
        mvc.perform(get("/api/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointWithTokenIsOk() throws Exception {
        String token = testUsers.tokenFor("dev");
        mvc.perform(get("/api/status").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
