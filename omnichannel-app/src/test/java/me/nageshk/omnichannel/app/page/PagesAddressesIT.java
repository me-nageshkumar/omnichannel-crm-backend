package me.nageshk.omnichannel.app.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import me.nageshk.omnichannel.app.support.MutableClock;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.page.PagesAddress;
import me.nageshk.omnichannel.db.page.PagesAddressRepository;
import me.nageshk.omnichannel.db.page.PagesAddressStatus;
import me.nageshk.omnichannel.db.page.PagesSettingsRepository;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.service.page.PagesAddresses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Link addresses spec §5.2–§5.5: which address a brand's links use, and setting one. */
@SpringBootTest
class PagesAddressesIT {

    @org.springframework.boot.test.context.TestConfiguration
    static class Fixtures {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-11-18T05:00:00Z"));
        }
    }

    static final String PLATFORM = "http://pages.test";

    @Autowired PagesAddresses addresses;
    @Autowired PagesAddressRepository repo;
    @Autowired PagesSettingsRepository settings;
    @Autowired TestUsers testUsers;
    @Autowired MutableClock clock;
    @Autowired MongoTemplate mongo;

    Customer acme;
    Customer other;
    Workspace a;
    Workspace b;
    Workspace foreign;
    Instant start;

    @BeforeEach
    void setUp() {
        clean();
        start = clock.instant();
        acme = testUsers.customer("Acme");
        other = testUsers.customer("Other");
        a = testUsers.workspace(acme.getId(), "Brand A");
        b = testUsers.workspace(acme.getId(), "Brand B");
        foreign = testUsers.workspace(other.getId(), "Foreign");
    }

    @AfterEach
    void tearDown() {
        clean();
        if (start != null) clock.set(start);
    }

    private void clean() {
        repo.deleteAll();
        settings.deleteAll();
        testUsers.deleteAll();
    }

    private PagesAddress set(Workspace w, String url) {
        Long version = repo.findByWorkspaceId(w.getId()).map(PagesAddress::getVersion).orElse(null);
        return addresses.set(w.getCustomerId(), w.getId(), url, version, "staff");
    }

    private void status(Workspace w, PagesAddressStatus s) {
        mongo.updateFirst(new Query(Criteria.where("workspaceId").is(w.getId())), new Update().set("status", s),
                PagesAddress.class);
    }

    @Test
    void noAddressUsesThePlatform() {
        assertThat(addresses.platform()).isEqualTo(PLATFORM);
        assertThat(addresses.forWorkspace(a.getId())).isEqualTo(PLATFORM);
    }

    @Test
    void uncheckedAndFailingUseThePlatform() {
        set(a, "https://links.acme.in");
        status(a, PagesAddressStatus.UNCHECKED);
        assertThat(addresses.forWorkspace(a.getId())).isEqualTo(PLATFORM);
        status(a, PagesAddressStatus.FAILING);
        assertThat(addresses.forWorkspace(a.getId())).isEqualTo(PLATFORM);
    }

    @Test
    void workingUsesTheBrands() {
        set(a, "https://acme.in/wa");
        status(a, PagesAddressStatus.WORKING);
        assertThat(addresses.forWorkspace(a.getId())).isEqualTo("https://acme.in/wa");
        assertThat(addresses.forWorkspace(b.getId())).isEqualTo(PLATFORM);
    }

    @Test
    void setNormalisesAndStoresTheHost() {
        PagesAddress p = set(a, " https://Links.Acme.in/ ");
        assertThat(p.getBaseUrl()).isEqualTo("https://links.acme.in");
        assertThat(p.getHost()).isEqualTo("links.acme.in");
        assertThat(p.getCustomerId()).isEqualTo(acme.getId());
        assertThat(p.getSetBy()).isEqualTo("staff");
    }

    @Test
    void setRefusesThePlatformHost() {
        assertThatThrownBy(() -> set(a, "http://pages.test/x")).isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).fields()).containsEntry("baseUrl",
                        "This is the platform's own address"));
    }

    @Test
    void setRefusesAnotherCustomersHost() {
        set(foreign, "https://links.shared.in");
        assertThatThrownBy(() -> set(a, "https://links.shared.in/other")).isInstanceOf(CodedException.class)
                .hasMessageContaining("Another customer uses this domain");
    }

    @Test
    void setRefusesAnotherCustomersFormerHostUntilItsEnd() {
        set(foreign, "https://old.shared.in");
        set(foreign, "https://new.shared.in");
        assertThatThrownBy(() -> set(a, "https://old.shared.in")).isInstanceOf(CodedException.class);
        clock.advance(Duration.ofDays(91));
        assertThat(set(a, "https://old.shared.in").getHost()).isEqualTo("old.shared.in");
    }

    @Test
    void brandsOfOneCustomerMayShareAnAddress() {
        set(a, "https://links.acme.in");
        assertThat(set(b, "https://links.acme.in").getHost()).isEqualTo("links.acme.in");
    }

    @Test
    void changingKeepsTheOldHostKnownForTheTtl() {
        set(a, "https://old.acme.in");
        set(a, "https://new.acme.in");
        clock.advance(Duration.ofDays(89));
        assertThat(addresses.hostKnown("old.acme.in")).isTrue();
        assertThat(addresses.customerHost("old.acme.in")).isTrue();
        clock.advance(Duration.ofDays(2));
        assertThat(addresses.hostKnown("old.acme.in")).isFalse();
        set(a, "https://third.acme.in");
        assertThat(repo.findByWorkspaceId(a.getId()).orElseThrow().getFormerHosts())
                .extracting(f -> f.host()).containsExactly("new.acme.in");
    }

    @Test
    void settingAFormerHostAgainTakesItOutOfFormer() {
        set(a, "https://old.acme.in");
        set(a, "https://new.acme.in");
        set(a, "https://old.acme.in");
        assertThat(repo.findByWorkspaceId(a.getId()).orElseThrow().getFormerHosts()).extracting(f -> f.host())
                .containsExactly("new.acme.in");
    }

    @Test
    void removeKeepsTheHostKnown() {
        set(a, "https://links.acme.in");
        PagesAddress p = addresses.remove(acme.getId(), a.getId(),
                repo.findByWorkspaceId(a.getId()).orElseThrow().getVersion(), "staff");
        assertThat(p.getBaseUrl()).isNull();
        assertThat(p.getHost()).isNull();
        assertThat(p.getStatus()).isNull();
        assertThat(addresses.hostKnown("links.acme.in")).isTrue();
        assertThat(addresses.forWorkspace(a.getId())).isEqualTo(PLATFORM);
    }

    @Test
    void aStaleVersionIs409() {
        set(a, "https://links.acme.in");
        assertThatThrownBy(() -> addresses.set(acme.getId(), a.getId(), "https://x.acme.in", 99L, "staff"))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void aWorkspaceOfAnotherCustomerIsNotFound() {
        assertThatThrownBy(() -> addresses.set(acme.getId(), foreign.getId(), "https://x.acme.in", null, "staff"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void savedPlatformWinsAndNullRestoresStartup() {
        PagesAddresses.PlatformView saved = addresses.savePlatform("https://go.example.in", null, "staff");
        assertThat(saved.source()).isEqualTo("SAVED");
        assertThat(saved.startupBaseUrl()).isEqualTo(PLATFORM);
        assertThat(addresses.platform()).isEqualTo("https://go.example.in");
        PagesAddresses.PlatformView back = addresses.savePlatform(null, saved.version(), "staff");
        assertThat(back.source()).isEqualTo("STARTUP");
        assertThat(addresses.platform()).isEqualTo(PLATFORM);
    }

    @Test
    void platformChangeKeepsItsOldHostKnown() {
        addresses.savePlatform("https://go.example.in", null, "staff");
        addresses.savePlatform("https://links.example.in", settings.findAll().get(0).getVersion(), "staff");
        assertThat(addresses.hostKnown("go.example.in")).isTrue();
        assertThat(addresses.customerHost("go.example.in")).isFalse();
        assertThat(addresses.platformHost()).isEqualTo("links.example.in");
    }

    @Test
    void platformCannotTakeABrandsHost() {
        set(a, "https://links.acme.in");
        assertThatThrownBy(() -> addresses.savePlatform("https://links.acme.in", null, "staff"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void aDisabledBrandKeepsItsAddressAndHostKnown() {
        set(a, "https://links.acme.in");
        status(a, PagesAddressStatus.WORKING);
        Workspace w = mongo.findById(a.getId(), Workspace.class);
        w.setDisabled(new me.nageshk.omnichannel.db.enablement.Disabled("staff",
                me.nageshk.omnichannel.common.enablement.EnableLevel.values()[0], Instant.now()));
        mongo.save(w);
        assertThat(addresses.hostKnown("links.acme.in")).isTrue();
        assertThat(addresses.forWorkspace(a.getId())).isEqualTo("https://links.acme.in");
        assertThat(addresses.forCustomer(acme.getId()).brands()).extracting(r -> r.workspaceName())
                .containsExactly("Brand A", "Brand B");
    }

    @Test
    void hostKnownIsCaseInsensitive() {
        set(a, "https://links.acme.in");
        assertThat(addresses.hostKnown("Links.ACME.in")).isTrue();
        assertThat(addresses.hostKnown("pages.test")).isTrue();
        assertThat(addresses.hostKnown("nobody.example")).isFalse();
        assertThat(addresses.hostKnown(null)).isFalse();
        assertThat(addresses.hostKnown(" ")).isFalse();
    }

    @Test
    void customerHostIsNeverThePlatformHost() {
        assertThat(addresses.customerHost("pages.test")).isFalse();
    }
}
