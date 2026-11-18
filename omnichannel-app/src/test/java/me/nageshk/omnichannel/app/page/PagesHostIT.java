package me.nageshk.omnichannel.app.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.page.FormerHost;
import me.nageshk.omnichannel.db.page.PagesAddress;
import me.nageshk.omnichannel.db.page.PagesAddressRepository;
import me.nageshk.omnichannel.db.page.PagesAddressStatus;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Link addresses spec §5.6–§5.7: a customer's host serves the pages only; the edge asks which hosts are ours. */
@SpringBootTest
@AutoConfigureMockMvc
class PagesHostIT {

    @Autowired MockMvc mvc;
    @Autowired PagesAddressRepository repo;
    @Autowired TestUsers testUsers;

    String token;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        testUsers.deleteAll();
        PagesAddress a = new PagesAddress();
        a.setWorkspaceId("w-acme");
        a.setCustomerId("c-acme");
        a.setBaseUrl("https://links.acme.in");
        a.setHost("links.acme.in");
        a.setStatus(PagesAddressStatus.UNCHECKED);
        a.setFormerHosts(List.of(new FormerHost("old.acme.in", Instant.now().plus(Duration.ofDays(10))),
                new FormerHost("gone.acme.in", Instant.now().minus(Duration.ofDays(1)))));
        repo.save(a);
        User root = testUsers.create("root", "pw", BuiltInRoles.SUPER_ADMIN, null);
        token = testUsers.accessToken(root);
    }

    @AfterEach
    void tearDown() {
        repo.deleteAll();
        testUsers.deleteAll();
    }

    private MvcResult on(String host, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Host", host)).andReturn();
    }

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder req, String remote) {
        return req.with(r -> {
            r.setRemoteAddr(remote);
            return r;
        });
    }

    @Test
    void onACustomerHostPagesAreServed() throws Exception {
        // the page handlers answer (an unknown token is their own not-found page, not an empty refusal)
        MvcResult page = on("links.acme.in", get("/t/no-such-token"));
        assertThat(page.getResponse().getStatus()).isEqualTo(404);
        assertThat(page.getResponse().getContentAsString()).isNotEmpty();
        MvcResult form = on("links.acme.in", get("/f/no-such-token"));
        assertThat(form.getResponse().getStatus()).isEqualTo(404);
        assertThat(form.getResponse().getContentAsString()).isNotEmpty();
        assertThat(on("links.acme.in:443", post("/t/no-such-token/e").contentType("application/json").content("{}"))
                .getResponse().getContentAsString()).isNotNull();
    }

    @Test
    void onACustomerHostTheConsoleAndApiAreNotFound() throws Exception {
        for (MockHttpServletRequestBuilder req : List.of(get("/"), get("/login"), get("/inbox"),
                get("/api/users/me").header("Authorization", "Bearer " + token), get("/ws"),
                get("/internal/pages/host-allowed").param("domain", "links.acme.in"),
                post("/api/auth/login").contentType("application/json").content("{}"))) {
            MvcResult r = on("Links.Acme.in", req);
            assertThat(r.getResponse().getStatus()).as(r.getRequest().getRequestURI()).isEqualTo(404);
            assertThat(r.getResponse().getContentAsString()).isEmpty();
        }
    }

    @Test
    void aFormerHostInTimeBehavesAsACustomerHost() throws Exception {
        assertThat(on("old.acme.in", get("/login")).getResponse().getStatus()).isEqualTo(404);
        assertThat(on("gone.acme.in", get("/login")).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void onThePlatformHostAndAnUnknownHostNothingChanges() throws Exception {
        assertThat(on("pages.test", get("/login")).getResponse().getStatus()).isEqualTo(200);
        assertThat(on("localhost", get("/login")).getResponse().getStatus()).isEqualTo(200);
        assertThat(on("pages.test", get("/api/users/me").header("Authorization", "Bearer " + token)).getResponse()
                .getStatus()).isEqualTo(200);
    }

    @Test
    void aForwardedHostHeaderIsIgnoredByDefault() throws Exception {
        MvcResult r = on("pages.test", get("/login").header("X-Forwarded-Host", "links.acme.in"));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void hostAllowedSaysYesForKnownHosts() throws Exception {
        for (String host : List.of("pages.test", "links.acme.in", "LINKS.acme.in", "old.acme.in")) {
            assertThat(on("localhost", get("/internal/pages/host-allowed").param("domain", host)).getResponse()
                    .getStatus()).as(host).isEqualTo(200);
        }
    }

    @Test
    void hostAllowedSaysNoForUnknownAndExpired() throws Exception {
        for (String host : List.of("gone.acme.in", "evil.example", "")) {
            assertThat(on("localhost", get("/internal/pages/host-allowed").param("domain", host)).getResponse()
                    .getStatus()).as(host).isEqualTo(404);
        }
    }

    @Test
    void hostAllowedDoesNotAnswerAProxiedOrRemoteCaller() throws Exception {
        assertThat(on("pages.test", get("/internal/pages/host-allowed").param("domain", "links.acme.in")
                .header("X-Forwarded-For", "198.51.100.7")).getResponse().getStatus()).isEqualTo(404);
        assertThat(on("pages.test", get("/internal/pages/host-allowed").param("domain", "links.acme.in")
                .header("Via", "1.1 caddy")).getResponse().getStatus()).isEqualTo(404);
        assertThat(on("pages.test", from(get("/internal/pages/host-allowed").param("domain", "links.acme.in"),
                "203.0.113.5")).getResponse().getStatus()).isEqualTo(404);
    }
}
