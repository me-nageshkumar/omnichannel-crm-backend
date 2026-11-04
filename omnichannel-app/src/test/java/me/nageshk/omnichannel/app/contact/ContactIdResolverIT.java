package me.nageshk.omnichannel.app.contact;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import me.nageshk.omnichannel.app.support.MutableClock;
import me.nageshk.omnichannel.app.support.TestDevices;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.contact.ContactSource;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.service.contact.ContactEvents;
import me.nageshk.omnichannel.service.events.WhatsAppEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A person seen under one WhatsApp id gets the other from the number's device, off the ingest thread: an id nobody
 * holds joins the contact, an id another contact holds merges the two.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"omnichannel.rpc.timeout=2s", "omnichannel.contacts.resolve-on-message=true"})
@Import(ContactIdResolverIT.Fixtures.class)
@ExtendWith(OutputCaptureExtension.class)
class ContactIdResolverIT {

    static final Instant NOW = Instant.parse("2026-11-04T05:30:00Z");
    private static final String PHONE = "919812345678";
    private static final String P = PHONE + "@c.us";
    private static final String L = "123456789012345@lid";
    private static final String L2 = "555556789012345@lid";
    private static final AtomicInteger COUNTER = new AtomicInteger();

    /** The threads {@code ContactsMerged} was published on. */
    static class MergeThreads {
        final List<String> names = new CopyOnWriteArrayList<>();

        @EventListener
        void on(ContactEvents.ContactsMerged e) {
            names.add(Thread.currentThread().getName());
        }
    }

    @TestConfiguration
    static class Fixtures {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }

        @Bean
        MergeThreads mergeThreads() {
            return new MergeThreads();
        }
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired MutableClock clock;
    @Autowired TestUsers testUsers;
    @Autowired TestDevices testDevices;
    @Autowired MongoTemplate mongo;
    @Autowired ObjectMapper mapper;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired ApplicationContext context;
    @Autowired ContactRepository contacts;
    @Autowired WaWebChatRepository chats;
    @Autowired MergeThreads mergeThreads;

    Customer acme;
    Workspace brand;
    PhoneNumber n1;
    PhoneNumber n2;
    User manager;
    User agent;

    @BeforeEach
    void setUp() {
        clean();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
        n1 = testDevices.number(brand, "9198" + (System.nanoTime() % 100000000));
        n2 = testDevices.number(brand, "9197" + (System.nanoTime() % 100000000));
        manager = brandUser("manager", BuiltInRoles.WORKSPACE_MANAGER);
        agent = brandUser("agent", BuiltInRoles.AGENT);
    }

    @AfterEach
    void tearDown() throws Exception {
        drain();
        clean();
    }

    private void clean() {
        testUsers.deleteAll();
        for (Class<?> c : List.of(Contact.class, WaWebChat.class, WaWebMessage.class, WaWebChatAction.class))
            mongo.remove(new Query(), c);
        mergeThreads.names.clear();
        clock.set(NOW);
    }

    private User brandUser(String username, String roleName) {
        return testUsers.createInCustomer(acme.getId(), username, null,
                List.of(new WorkspaceRole(brand.getId(), testUsers.roleId(roleName))));
    }

    private ResolverDevice device(PhoneNumber n, boolean ready) throws Exception {
        return new ResolverDevice(port, testDevices, n, ready);
    }

    /** Waits until every resolution queued so far has finished (the resolver runs one task at a time, in order). */
    private void drain() throws Exception {
        if (!context.containsBean("contactResolverExecutor")) {
            Thread.sleep(500);
            return;
        }
        context.getBean("contactResolverExecutor", AsyncTaskExecutor.class).submit(() -> { }).get(15, TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) throw new AssertionError("condition not met within 5 s");
            Thread.sleep(20);
        }
    }

    /** An incoming text message from {@code remote} on the number, stored the way the bridge stores one. */
    private void incoming(PhoneNumber n, String remote) {
        ObjectNode p = mapper.createObjectNode();
        ObjectNode id = p.putObject("id");
        id.put("fromMe", false);
        id.put("remote", remote);
        id.put("_serialized", "false_" + remote + "_" + COUNTER.incrementAndGet());
        p.put("fromMe", false);
        p.put("timestamp", clock.instant().getEpochSecond());
        p.put("type", "chat");
        p.put("body", "hi");
        publisher.publishEvent(new WhatsAppEvent(this, n.getId(), brand.getId(), acme.getId(), "message_create", p,
                null, null));
    }

    private Contact contact(String phone, String... waIds) {
        Contact c = new Contact();
        c.setWorkspaceId(brand.getId());
        c.setCustomerId(acme.getId());
        c.setPhone(phone);
        c.setSource(ContactSource.CHAT);
        c.setWaIds(new ArrayList<>(List.of(waIds)));
        return contacts.save(c);
    }

    private WaWebChat chat(PhoneNumber n, String remoteId, Contact c) {
        WaWebChat chat = new WaWebChat();
        chat.setCustomerId(acme.getId());
        chat.setWorkspaceId(brand.getId());
        chat.setPhoneNumberId(n.getId());
        chat.setRemoteId(remoteId);
        chat.setContactId(c.getId());
        chat.setStatus(ChatStatus.OPEN);
        return chats.save(chat);
    }

    private List<Contact> brandContacts() {
        return mongo.find(new Query(Criteria.where("workspaceId").is(brand.getId())), Contact.class);
    }

    private Contact only() {
        List<Contact> all = brandContacts();
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    private ResponseEntity<JsonNode> resolveIds(User who) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(testUsers.accessToken(who));
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + "/api/workspaces/" + brand.getId() + "/contacts/resolve-ids",
                HttpMethod.POST, new HttpEntity<>(h), JsonNode.class);
    }

    @Test
    void phoneChatJoinsTheLidContactAfterResolution() throws Exception {
        Contact hidden = contact(null, L);
        chat(n1, L, hidden);
        try (ResolverDevice d = device(n1, true)) {
            d.knows(P, L);
            incoming(n1, P);
            drain();

            Contact one = only();
            assertThat(one.getId()).isNotEqualTo(hidden.getId());
            assertThat(one.getPhone()).isEqualTo(PHONE);
            assertThat(one.getWaIds()).containsExactly(P, L);
            assertThat(chats.findAll()).hasSize(2).extracting(WaWebChat::getContactId).containsOnly(one.getId());
            assertThat(d.requests("resolveIds")).hasSize(1).first().extracting(ResolverDevice.Request::id)
                    .isEqualTo(P);
            assertThat(mergeThreads.names).hasSize(1);
        }
    }

    @Test
    void counterpartNobodyHoldsIsAdded() throws Exception {
        try (ResolverDevice d = device(n1, true)) {
            d.knows(P, L2);
            incoming(n1, P);
            drain();

            Contact one = only();
            assertThat(one.getWaIds()).containsExactly(P, L2);
            assertThat(one.getPhone()).isEqualTo(PHONE);
            assertThat(mergeThreads.names).isEmpty();
            assertThat(d.requests("resolveIds")).hasSize(1);
        }
    }

    @Test
    void lidContactGainsThePhoneNobodyHolds() throws Exception {
        try (ResolverDevice d = device(n1, true)) {
            d.knows(P, L);
            incoming(n1, L);
            drain();

            Contact one = only();
            assertThat(one.getWaIds()).containsExactly(L, P);
            assertThat(one.getPhone()).isEqualTo(PHONE);
            assertThat(d.requests("resolveIds")).hasSize(1).first().extracting(ResolverDevice.Request::id)
                    .isEqualTo(L);
        }
    }

    @Test
    void unknownCounterpartLeavesTheContact() throws Exception {
        try (ResolverDevice d = device(n1, true)) {
            incoming(n1, L);
            drain();

            Contact one = only();
            assertThat(one.getWaIds()).containsExactly(L);
            assertThat(one.getPhone()).isNull();
            assertThat(d.requests("resolveIds")).hasSize(1);
        }
    }

    @Test
    void idInAMergeStashIsLeftToTheMerge() throws Exception {
        Contact survivor = contact("919811111111", "919811111111@c.us");
        Contact merging = contact(null);
        merging.setMergingInto(survivor.getId());
        merging.setMergingWaIds(new ArrayList<>(List.of(L)));
        contacts.save(merging);
        try (ResolverDevice d = device(n1, true)) {
            d.knows(P, L);
            incoming(n1, P);
            drain();

            assertThat(d.requests("resolveIds")).hasSize(1);
            Contact b = contacts.findByWorkspaceIdAndPhone(brand.getId(), PHONE).orElseThrow();
            assertThat(b.getWaIds()).containsExactly(P);
            assertThat(contacts.findById(merging.getId()).orElseThrow().getMergingWaIds()).containsExactly(L);
            assertThat(contacts.findById(survivor.getId()).orElseThrow().getWaIds())
                    .containsExactly("919811111111@c.us");
            assertThat(mergeThreads.names).isEmpty();
        }
    }

    @Test
    void oneAttemptPerHour() throws Exception {
        try (ResolverDevice d = device(n1, true)) {
            incoming(n1, P);
            drain();
            clock.advance(Duration.ofMinutes(30));
            incoming(n1, P);
            drain();
            assertThat(d.requests("resolveIds")).hasSize(1);

            clock.advance(Duration.ofMinutes(30));
            incoming(n1, P);
            drain();
            assertThat(d.requests("resolveIds")).hasSize(2);
        }
    }

    @Test
    void notReadyDeviceSkips(CapturedOutput output) throws Exception {
        int before = output.getAll().length();
        try (ResolverDevice d = device(n1, false)) {
            incoming(n1, L);
            drain();

            assertThat(d.requests()).isEmpty();
            assertThat(only().getWaIds()).containsExactly(L);
        }
        assertThat(output.getAll().substring(before)).doesNotContain("resolveIds").doesNotContain("WARN")
                .doesNotContain("ERROR");
    }

    @Test
    void outdatedExtensionIsLoggedOnceAndChangesNothing(CapturedOutput output) throws Exception {
        int before = output.getAll().length();
        try (ResolverDevice d = device(n1, true)) {
            d.outdated = true;
            incoming(n1, L);
            incoming(n1, L2);
            drain();

            assertThat(d.requests("resolveIds")).hasSize(2);
            assertThat(brandContacts()).extracting(Contact::getWaIds)
                    .containsExactlyInAnyOrder(List.of(L), List.of(L2));
        }
        String logged = output.getAll().substring(before);
        assertThat(logged.split("cannot look up contacts' other WhatsApp ids", -1)).hasSize(2);
        assertThat(logged).doesNotContain("ERROR");
    }

    @Test
    void endpointQueuesUnresolvedContacts() throws Exception {
        Contact hidden = contact(null, L); // counted: no phone
        chat(n1, L, hidden);
        Contact phoneOnly = contact(PHONE, P); // counted: no lid
        chat(n1, P, phoneOnly);
        Contact resolved = contact("919822222222", "919822222222@c.us", L2); // not counted
        chat(n1, "919822222222@c.us", resolved);
        Contact otherNumber = contact(null, "777776789012345@lid"); // not counted: its number has no device
        chat(n2, "777776789012345@lid", otherNumber);
        contact(null, "888886789012345@lid"); // not counted: no chat

        try (ResolverDevice d = device(n1, true)) {
            assertThat(resolveIds(agent).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            ResponseEntity<JsonNode> res = resolveIds(manager);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(res.getBody().get("queued").asInt()).isEqualTo(2);
            drain();

            List<ResolverDevice.Request> asked = d.requests("resolveIds");
            assertThat(asked).extracting(ResolverDevice.Request::id).containsExactlyInAnyOrder(L, P);
            // the device is asked at most once per pace (50 ms in tests)
            long gap = Math.abs(asked.get(1).arrivedNanos() - asked.get(0).arrivedNanos());
            assertThat(Duration.ofNanos(gap)).isGreaterThanOrEqualTo(Duration.ofMillis(40));
        }
    }

    @Test
    void secondBrandRequestQueuesNothingWhileTheFirstIsPending() throws Exception {
        Contact hidden = contact(null, L);
        chat(n1, L, hidden);
        Contact phoneOnly = contact(PHONE, P);
        chat(n1, P, phoneOnly);
        try (ResolverDevice d = device(n1, true)) {
            CountDownLatch held = new CountDownLatch(1);
            d.gate = held;

            assertThat(resolveIds(manager).getBody().get("queued").asInt()).isEqualTo(2);
            await(() -> d.requests("resolveIds").size() == 1);
            // one contact waits on the device, the other in the queue: neither is queued again
            assertThat(resolveIds(manager).getBody().get("queued").asInt()).isZero();

            held.countDown();
            drain();
            assertThat(d.requests("resolveIds")).extracting(ResolverDevice.Request::id)
                    .containsExactlyInAnyOrder(L, P);
            // once done, the contacts can be queued again
            assertThat(resolveIds(manager).getBody().get("queued").asInt()).isEqualTo(2);
        }
    }

    @Test
    void aPendingContactIsNotQueuedTwiceByMessages() throws Exception {
        try (ResolverDevice d = device(n1, true)) {
            CountDownLatch held = new CountDownLatch(1);
            d.gate = held;

            incoming(n1, L);
            await(() -> d.requests("resolveIds").size() == 1);
            // the hour has passed, but the first lookup is still waiting on the device
            clock.advance(Duration.ofHours(1));
            incoming(n1, L);

            held.countDown();
            drain();
            assertThat(d.requests("resolveIds")).hasSize(1);
            assertThat(only().getWaIds()).containsExactly(L);
        }
    }

    @Test
    void endpointRetriesAStalledMerge() throws Exception {
        Contact survivor = contact(PHONE, P);
        Contact stalled = contact(null);
        stalled.setMergingInto(survivor.getId());
        stalled.setMergingWaIds(new ArrayList<>(List.of(L)));
        contacts.save(stalled);
        chat(n1, L, stalled);

        ResponseEntity<JsonNode> res = resolveIds(manager);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(res.getBody().get("queued").asInt()).isEqualTo(1);
        drain();

        Contact one = only();
        assertThat(one.getId()).isEqualTo(survivor.getId());
        assertThat(one.getWaIds()).containsExactly(P, L);
        assertThat(chats.findAll()).extracting(WaWebChat::getContactId).containsExactly(survivor.getId());
        assertThat(mergeThreads.names).hasSize(1);
    }

    @Test
    void resolutionNeverRunsOnTheIngestThread() throws Exception {
        Contact hidden = contact(null, L);
        chat(n1, L, hidden);
        try (ResolverDevice d = device(n1, true)) {
            d.knows(P, L);
            CountDownLatch held = new CountDownLatch(1);
            d.gate = held;

            long started = System.nanoTime();
            incoming(n1, P);
            // the message is stored without waiting on the device, which holds its answer
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            assertThat(brandContacts()).hasSize(2);
            await(() -> d.requests("resolveIds").size() == 1);

            held.countDown();
            drain();
            assertThat(only().getWaIds()).containsExactly(P, L);
            assertThat(mergeThreads.names).hasSize(1).allMatch(name -> name.startsWith("contact-resolver-"));
        }
    }
}
