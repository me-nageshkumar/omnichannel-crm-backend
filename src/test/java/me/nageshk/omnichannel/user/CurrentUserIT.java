package me.nageshk.omnichannel.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import me.nageshk.omnichannel.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class CurrentUserIT {

    @Autowired MockMvc mvc;
    @Autowired TestUsers testUsers;
    @Autowired UserRepository repo;

    @BeforeEach
    void clean() {
        testUsers.deleteAll();
    }

    private User superAdmin() {
        User u = testUsers.create("nageshk", "pw", "SUPER_ADMIN", TestUsers.TEST_SECRET);
        u.setName("Nagesh Kumar");
        u.setEmail("me@example.in");
        u.setMobileNumber("9928088743");
        return repo.save(u);
    }

    private ResultActions getMe(String token) throws Exception {
        return mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token));
    }

    @Test
    void returnsProfileOfCaller() throws Exception {
        User u = superAdmin();
        getMe(testUsers.accessToken(u))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(u.getId()))
                .andExpect(jsonPath("$.username").value("nageshk"))
                .andExpect(jsonPath("$.name").value("Nagesh Kumar"))
                .andExpect(jsonPath("$.email").value("me@example.in"))
                .andExpect(jsonPath("$.mobileNumber").value("9928088743"))
                .andExpect(jsonPath("$.role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.twoFactorEnabled").value(true));
    }

    @Test
    void neverExposesSecrets() throws Exception {
        getMe(testUsers.accessToken(superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.tokenVersion").doesNotExist())
                .andExpect(jsonPath("$.twoFactor").doesNotExist())
                .andExpect(jsonPath("$.totpSecret").doesNotExist());
    }

    @Test
    void reflectsTwoFactorChangeWithoutNewToken() throws Exception {
        User u = testUsers.create("dev", "pw", "WHATSAPP_DEVICE", null);
        String token = testUsers.accessToken(u);
        u.setTwoFactor(TwoFactor.enabledWith(TestUsers.TEST_SECRET));
        repo.save(u);

        getMe(token).andExpect(status().isOk()).andExpect(jsonPath("$.twoFactorEnabled").value(true));
    }

    @Test
    void requiresToken() throws Exception {
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    }
}
