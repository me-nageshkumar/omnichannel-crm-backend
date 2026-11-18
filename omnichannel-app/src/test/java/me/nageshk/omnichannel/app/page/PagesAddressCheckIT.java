package me.nageshk.omnichannel.app.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.page.PagesAddress;
import me.nageshk.omnichannel.db.page.PagesAddressRepository;
import me.nageshk.omnichannel.db.page.PagesAddressStatus;
import me.nageshk.omnichannel.db.page.PagesSettingsRepository;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.service.page.HttpPagesProbe;
import me.nageshk.omnichannel.service.page.PagesAddressChecker;
import me.nageshk.omnichannel.service.page.PagesAddresses;
import me.nageshk.omnichannel.service.page.PagesProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Link addresses spec §5.4: a check proves an address reaches this server; the daily pass needs three failures. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class PagesAddressCheckIT {

    static final String PLATFORM = "http://pages.test";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired PagesAddresses addresses;
    @Autowired PagesAddressChecker checker;
    @Autowired PagesAddressRepository repo;
    @Autowired PagesSettingsRepository settings;
    @Autowired TestUsers testUsers;
    @Autowired MongoTemplate mongo;
    @MockitoSpyBean HttpPagesProbe probe;

    Workspace a;

    @BeforeEach
    void setUp() {
        clean();
        Customer acme = testUsers.customer("Acme");
        a = testUsers.workspace(acme.getId(), "Brand A");
    }

    @AfterEach
    void tearDown() {
        reset(probe);
        clean();
    }

    private void clean() {
        repo.deleteAll();
        settings.deleteAll();
        testUsers.deleteAll();
    }

    private String self() {
        return "http://localhost:" + port;
    }

    private PagesAddress set(String url) {
        Long version = repo.findByWorkspaceId(a.getId()).map(PagesAddress::getVersion).orElse(null);
        return addresses.set(a.getCustomerId(), a.getId(), url, version, "staff");
    }

    private PagesAddress stored() {
        return repo.findByWorkspaceId(a.getId()).orElseThrow();
    }

    /** The probe answers as this server would, for whatever nonce it was asked about. */
    private void answersLikeUs() {
        doAnswer(inv -> {
            String url = inv.getArgument(0);
            return new PagesProbe.Result(200, "omnichannel-pages " + url.substring(url.lastIndexOf('/') + 1), null);
        }).when(probe).get(anyString());
    }

    private void fails(String failure) {
        doReturn(new PagesProbe.Result(0, null, failure)).when(probe).get(anyString());
    }

    @Test
    void aWorkingAddressIsMarkedWorking() {
        PagesAddress p = set(self());
        assertThat(p.getStatus()).isEqualTo(PagesAddressStatus.WORKING);
        assertThat(p.getCheckedAt()).isNotNull();
        assertThat(p.getLastError()).isNull();
        assertThat(addresses.forWorkspace(a.getId())).isEqualTo(self());
    }

    @Test
    void aFolderNobodyStripsIsNotWorking() {
        PagesAddress p = set(self() + "/wa");
        assertThat(p.getStatus()).isEqualTo(PagesAddressStatus.FAILING);
        assertThat(p.getLastError()).isEqualTo("The address answered, but not from this server");
    }

    @Test
    void anotherSitesAnswerIsNotWorking() {
        doReturn(new PagesProbe.Result(200, "<html>Welcome to Bimal Institute</html>", null)).when(probe).get(anyString());
        PagesAddress p = set("https://links.acme.in");
        assertThat(p.getStatus()).isEqualTo(PagesAddressStatus.FAILING);
        assertThat(p.getLastError()).isEqualTo("The address answered, but not from this server");
    }

    @Test
    void aRedirectIsNotFollowed() {
        doReturn(new PagesProbe.Result(302, "", null)).when(probe).get(anyString());
        assertThat(set("https://links.acme.in").getStatus()).isEqualTo(PagesAddressStatus.FAILING);
    }

    @Test
    void noAnswerBadCertificateAndUnknownNameSayWhy() {
        fails(PagesProbe.NO_ANSWER);
        assertThat(set("https://links.acme.in").getLastError()).isEqualTo("The address did not answer");
        fails(PagesProbe.BAD_CERTIFICATE);
        assertThat(checker.check(a.getId()).getLastError()).isEqualTo("The certificate is not valid");
        fails(PagesProbe.NO_SUCH_NAME);
        assertThat(checker.check(a.getId()).getLastError()).isEqualTo("The name does not exist");
        fails(PagesProbe.REFUSED_ADDRESS);
        assertThat(checker.check(a.getId()).getLastError()).isEqualTo("The address points to a private network");
    }

    @Test
    void theCheckEndpointAnswersOnlyTheStoredNonce() {
        set(self());
        String nonce = stored().getCheckNonce();
        ResponseEntity<String> ok = rest.getForEntity(self() + "/t/-/check/" + nonce, String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).isEqualTo("omnichannel-pages " + nonce);
        assertThat(rest.getForEntity(self() + "/t/-/check/someoneElsesNonce", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        checker.check(a.getId());
        assertThat(rest.getForEntity(self() + "/t/-/check/" + nonce, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aResultForAnAddressThatChangedIsDropped() {
        set(self());
        doAnswer(inv -> {
            // someone saves another address while this check waits for its answer
            mongo.updateFirst(new Query(Criteria.where("workspaceId").is(a.getId())), new Update()
                    .set("baseUrl", "https://new.acme.in").set("host", "new.acme.in")
                    .set("status", PagesAddressStatus.UNCHECKED).unset("checkNonce"), PagesAddress.class);
            String url = inv.getArgument(0);
            return new PagesProbe.Result(200, "omnichannel-pages " + url.substring(url.lastIndexOf('/') + 1), null);
        }).when(probe).get(anyString());

        checker.check(a.getId());

        PagesAddress after = stored();
        assertThat(after.getBaseUrl()).isEqualTo("https://new.acme.in");
        assertThat(after.getStatus()).isEqualTo(PagesAddressStatus.UNCHECKED);
    }

    @Test
    void aManualFailureMarksFailingAtOnce() {
        set(self());
        fails(PagesProbe.NO_ANSWER);
        assertThat(checker.check(a.getId()).getStatus()).isEqualTo(PagesAddressStatus.FAILING);
        assertThat(addresses.forWorkspace(a.getId())).isEqualTo(PLATFORM);
    }

    @Test
    void theDailyPassNeedsThreeFailuresInARow() {
        set(self());
        fails(PagesProbe.NO_ANSWER);
        checker.recheckAll();
        checker.recheckAll();
        assertThat(stored().getStatus()).isEqualTo(PagesAddressStatus.WORKING);
        assertThat(stored().getFailures()).isEqualTo(2);
        assertThat(addresses.forWorkspace(a.getId())).isEqualTo(self());
        checker.recheckAll();
        assertThat(stored().getStatus()).isEqualTo(PagesAddressStatus.FAILING);
        assertThat(addresses.forWorkspace(a.getId())).isEqualTo(PLATFORM);
    }

    @Test
    void theDailyPassRecoversAFailingAddress() {
        fails(PagesProbe.NO_ANSWER);
        set("https://links.acme.in");
        assertThat(stored().getStatus()).isEqualTo(PagesAddressStatus.FAILING);
        answersLikeUs();
        checker.recheckAll();
        assertThat(stored().getStatus()).isEqualTo(PagesAddressStatus.WORKING);
        assertThat(stored().getFailures()).isZero();
    }

    @Test
    void anUncheckedAddressFailsAtOnceInTheDailyPass() {
        set(self());
        mongo.updateFirst(new Query(Criteria.where("workspaceId").is(a.getId())),
                new Update().set("status", PagesAddressStatus.UNCHECKED), PagesAddress.class);
        fails(PagesProbe.NO_ANSWER);
        checker.recheckAll();
        assertThat(stored().getStatus()).isEqualTo(PagesAddressStatus.FAILING);
    }

    @Test
    void savingThePlatformAddressWarnsWhenItDoesNotAnswer() {
        fails(PagesProbe.NO_ANSWER);
        PagesAddresses.PlatformView v = addresses.savePlatform("https://go.example.in", null, "staff");
        assertThat(v.warning()).isEqualTo("The address did not answer");
        assertThat(addresses.platform()).isEqualTo("https://go.example.in");
    }

    @Test
    void savingAPlatformAddressThatReachesUsHasNoWarning() {
        assertThat(addresses.savePlatform(self(), null, "staff").warning()).isNull();
    }
}
