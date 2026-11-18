package me.nageshk.omnichannel.app.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import me.nageshk.omnichannel.db.page.PagesAddress;
import me.nageshk.omnichannel.db.page.PagesAddressRepository;
import me.nageshk.omnichannel.db.page.PagesAddressStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Link addresses spec §5.6–§5.7 with a trusted proxy in front: its forwarded host counts, and it may ask. */
@SpringBootTest(properties = {"omnichannel.pages.trust-forwarded-host=true", "omnichannel.pages.ask-from=10.0.0.9"})
@AutoConfigureMockMvc
class PagesHostTrustedIT {

    @Autowired MockMvc mvc;
    @Autowired PagesAddressRepository repo;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        PagesAddress a = new PagesAddress();
        a.setWorkspaceId("w-acme");
        a.setCustomerId("c-acme");
        a.setBaseUrl("https://links.acme.in");
        a.setHost("links.acme.in");
        a.setStatus(PagesAddressStatus.WORKING);
        repo.save(a);
    }

    @AfterEach
    void tearDown() {
        repo.deleteAll();
    }

    @Test
    void aForwardedHostHeaderIsUsedWhenTrusted() throws Exception {
        assertThat(mvc.perform(get("/login").header("Host", "pages.test").header("X-Forwarded-Host", "links.acme.in"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void hostAllowedAnswersAnAskFromAddress() throws Exception {
        assertThat(mvc.perform(get("/internal/pages/host-allowed").param("domain", "links.acme.in").with(r -> {
            r.setRemoteAddr("10.0.0.9");
            return r;
        })).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
