package me.nageshk.omnichannel.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import me.nageshk.omnichannel.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The console's index.html here is the test-classpath stub in src/test/resources/static. */
@SpringBootTest
@AutoConfigureMockMvc
class SpaForwardIT {

    @Autowired MockMvc mvc;
    @Autowired TestUsers testUsers;

    @BeforeEach
    void clean() {
        testUsers.deleteAll();
    }

    @Test
    void rootServesIndex() throws Exception {
        MvcResult r = mvc.perform(get("/")).andExpect(status().isOk()).andReturn();
        String forwarded = r.getResponse().getForwardedUrl();
        if (forwarded == null) {
            assertThat(r.getResponse().getContentAsString()).contains("<title>test</title>");
        } else {
            assertThat(forwarded).isIn("/index.html", "index.html");
        }
    }

    @Test
    void deepLinksForwardToIndex() throws Exception {
        mvc.perform(get("/profile")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/login/verify")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void staticFileIsNotForwarded() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>test</title>")));
        mvc.perform(get("/missing.js")).andExpect(status().isNotFound()).andExpect(forwardedUrl(null));
    }

    @Test
    void unknownApiPathIsNotTheSpa() throws Exception {
        mvc.perform(get("/api/nope")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/nope").header("Authorization", "Bearer " + testUsers.tokenFor("dev")))
                .andExpect(status().isNotFound())
                .andExpect(forwardedUrl(null));
    }

    @Test
    void wsPathNotForwarded() throws Exception {
        mvc.perform(get("/ws/anything")).andExpect(forwardedUrl(null));
    }

    @Test
    void postToSpaPathIsNotPermitted() throws Exception {
        mvc.perform(post("/profile")).andExpect(status().isUnauthorized());
    }
}
