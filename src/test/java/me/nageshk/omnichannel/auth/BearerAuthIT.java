package me.nageshk.omnichannel.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class BearerAuthIT {

    @Autowired MockMvc mvc;
    @Autowired TestUsers testUsers;
    @Autowired UserRepository repo;
    @Autowired JwtService jwt;

    private User user;

    @BeforeEach
    void seed() {
        testUsers.deleteAll();
        user = testUsers.create("dev", "pw", UserRole.USER, null);
    }

    private ResultActions getStatus(String token) throws Exception {
        return mvc.perform(get("/api/status").header("Authorization", "Bearer " + token));
    }

    @Test
    void validAccessTokenIsAccepted() throws Exception {
        getStatus(testUsers.accessToken(user)).andExpect(status().isOk());
    }

    @Test
    void pendingTokenRejectedOnProtectedEndpoint() throws Exception {
        getStatus(jwt.issuePendingToken("dev")).andExpect(status().isUnauthorized());
    }

    @Test
    void staleTokenVersionRejected() throws Exception {
        String token = testUsers.accessToken(user);
        user.setTokenVersion(1L);
        repo.save(user);
        getStatus(token).andExpect(status().isUnauthorized());
    }

    @Test
    void deactivatedUserRejectedWithValidToken() throws Exception {
        String token = testUsers.accessToken(user);
        user.setActive(false);
        repo.save(user);
        getStatus(token).andExpect(status().isUnauthorized());
    }

    @Test
    void deletedUserRejected() throws Exception {
        String token = testUsers.accessToken(user);
        repo.delete(user);
        getStatus(token).andExpect(status().isUnauthorized());
    }
}
