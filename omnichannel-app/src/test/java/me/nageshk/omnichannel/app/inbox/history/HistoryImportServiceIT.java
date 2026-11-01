package me.nageshk.omnichannel.app.inbox.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.app.support.TestDevices;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.enablement.EnableLevel;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.contact.ContactSource;
import me.nageshk.omnichannel.db.enablement.Disabled;
import me.nageshk.omnichannel.db.inbox.HistoryChatStatus;
import me.nageshk.omnichannel.db.inbox.HistoryImportStatus;
import me.nageshk.omnichannel.db.inbox.HistoryScopeKind;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChat;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportRepository;
import me.nageshk.omnichannel.db.phone.ConnectionType;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.inbox.history.HistoryImportCounter;
import me.nageshk.omnichannel.service.inbox.history.HistoryImportRequest;
import me.nageshk.omnichannel.service.inbox.history.HistoryImportRequest.ScopeRequest;
import me.nageshk.omnichannel.service.inbox.history.HistoryImportService;
import me.nageshk.omnichannel.service.inbox.history.HistoryImportView;
import me.nageshk.omnichannel.service.inbox.history.HistoryImportViews;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest
class HistoryImportServiceIT {

    @Autowired HistoryImportService service;
    @Autowired HistoryImportCounter counter;
    @Autowired HistoryImportViews views;
    @Autowired TestUsers testUsers;
    @Autowired TestDevices testDevices;
    @Autowired WaWebHistoryImportRepository imports;
    @Autowired WaWebHistoryImportChatRepository items;
    @Autowired WaWebChatRepository chats;
    @Autowired ContactRepository contacts;
    @Autowired MongoTemplate mongo;

    Customer acme;
    Workspace brand;
    PhoneNumber number;
    PhoneNumber other;
    AuthenticatedUser me;

    @BeforeEach
    void setUp() {
        clean();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
        number = testDevices.number(brand, "9199" + (System.nanoTime() % 100000000));
        other = testDevices.number(brand, "9188" + (System.nanoTime() % 100000000));
        User u = testUsers.createInCustomer(acme.getId(), "importer", null, List.of());
        me = new AuthenticatedUser(u, Set.of());
    }

    @AfterEach
    void clean() {
        for (Class<?> c : List.of(WaWebHistoryImport.class, WaWebHistoryImportChat.class, WaWebChat.class,
                Contact.class))
            mongo.remove(new Query(), c);
        testUsers.deleteAll();
    }

    private static HistoryImportRequest all(Instant from, Instant to) {
        return new HistoryImportRequest(new ScopeRequest(HistoryScopeKind.ALL, null, null), from, to);
    }

    private static HistoryImportRequest chatsOf(List<String> phones, List<String> contactIds) {
        return new HistoryImportRequest(new ScopeRequest(HistoryScopeKind.CHATS, phones, contactIds), null, null);
    }

    private Contact contact(String phone, String waId) {
        Contact c = new Contact();
        c.setWorkspaceId(brand.getId());
        c.setCustomerId(acme.getId());
        c.setName("C " + phone);
        c.setPhone(phone);
        c.setWaId(waId);
        c.setSource(ContactSource.CHAT);
        return contacts.save(c);
    }

    private WaWebHistoryImportChat item(WaWebHistoryImport imp, String remoteId, HistoryChatStatus status,
            WaWebHistoryImportChat.Counts counts) {
        WaWebHistoryImportChat i = new WaWebHistoryImportChat();
        i.setImportId(imp.getId());
        i.setWorkspaceId(brand.getId());
        i.setPhoneNumberId(imp.getPhoneNumberId());
        i.setRemoteId(remoteId);
        i.setStatus(status);
        i.setCounts(counts);
        return items.save(i);
    }

    @Test
    void allScopeCreatesQueuedImportWithoutItems() {
        var created = service.create(me, brand.getId(), number.getId(), all(null, null));
        assertThat(created.created()).isTrue();
        WaWebHistoryImport imp = created.imp();
        assertThat(imp.getStatus()).isEqualTo(HistoryImportStatus.QUEUED);
        assertThat(imp.isActive()).isTrue();
        assertThat(imp.getCounts()).isEqualTo(WaWebHistoryImport.Counts.ZERO);
        assertThat(imp.getRequestedByUserId()).isEqualTo(me.userId());
        assertThat(imp.getRequestedByName()).isEqualTo("importer");
        assertThat(imp.getCustomerId()).isEqualTo(acme.getId());
        assertThat(imp.getWorkspaceId()).isEqualTo(brand.getId());
        assertThat(items.countByImportId(imp.getId())).isZero();
    }

    @Test
    void chatsScopeResolvesPhonesAndLidToItems() {
        contact("919811100001", "1234@lid");
        var created = service.create(me, brand.getId(), number.getId(), chatsOf(List.of("+91 98111 00001"), null));
        WaWebHistoryImport imp = created.imp();
        List<WaWebHistoryImportChat> rows = items.findAll();
        assertThat(rows).extracting(WaWebHistoryImportChat::getRemoteId)
                .containsExactlyInAnyOrder("919811100001@c.us", "1234@lid");
        assertThat(rows).allMatch(r -> r.getStatus() == HistoryChatStatus.PENDING);
        assertThat(imp.getCounts().chatsTotal()).isEqualTo(2);
        assertThat(imports.findById(imp.getId()).orElseThrow().getCounts().chatsTotal()).isEqualTo(2);
    }

    @Test
    void identicalActiveRequestIsReturned() {
        var first = service.create(me, brand.getId(), number.getId(), all(null, null));
        var second = service.create(me, brand.getId(), number.getId(), all(null, null));
        assertThat(second.created()).isFalse();
        assertThat(second.imp().getId()).isEqualTo(first.imp().getId());
    }

    @Test
    void identicalChatsRequestLeavesNoOrphanItems() {
        var first = service.create(me, brand.getId(), number.getId(), chatsOf(List.of("919811100003"), null));
        assertThat(items.countByImportId(first.imp().getId())).isEqualTo(first.imp().getScope().remoteIds().size());
        long before = items.count();
        var second = service.create(me, brand.getId(), number.getId(), chatsOf(List.of("919811100003"), null));
        assertThat(second.created()).isFalse();
        assertThat(second.imp().getId()).isEqualTo(first.imp().getId());
        assertThat(items.count()).isEqualTo(before);
    }

    @Test
    void differentRangeQueuesAnotherImport() {
        var first = service.create(me, brand.getId(), number.getId(), all(null, null));
        var second = service.create(me, brand.getId(), number.getId(), all(Instant.parse("2026-01-01T00:00:00Z"), null));
        assertThat(second.created()).isTrue();
        assertThat(second.imp().getId()).isNotEqualTo(first.imp().getId());
    }

    @Test
    void completedImportDoesNotBlockANewOne() {
        var first = service.create(me, brand.getId(), number.getId(), all(null, null));
        WaWebHistoryImport done = first.imp();
        done.setActive(false);
        done.setStatus(HistoryImportStatus.COMPLETED);
        imports.save(done);
        var second = service.create(me, brand.getId(), number.getId(), all(null, null));
        assertThat(second.created()).isTrue();
        assertThat(second.imp().getId()).isNotEqualTo(done.getId());
    }

    @Test
    void fromAfterToIsValidation() {
        Instant to = Instant.parse("2026-01-01T00:00:00Z");
        assertThatThrownBy(() -> service.create(me, brand.getId(), number.getId(), all(to.plusSeconds(1), to)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("must not be after to");
    }

    @Test
    void emptyChatsScopeIsValidation() {
        assertThatThrownBy(() -> service.create(me, brand.getId(), number.getId(), chatsOf(List.of(), List.of())))
                .isInstanceOf(ValidationException.class).hasMessageContaining("choose at least one contact");
    }

    @Test
    void invalidPhoneIsValidation() {
        assertThatThrownBy(() -> service.create(me, brand.getId(), number.getId(), chatsOf(List.of("12"), null)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void cloudApiNumberIsRejected() {
        number.setConnectionType(ConnectionType.WHATSAPP_CLOUD_API);
        mongo.save(number);
        assertThatThrownBy(() -> service.create(me, brand.getId(), number.getId(), all(null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("only WhatsApp Web numbers can import history");
        WaWebChat chat = new WaWebChat();
        chat.setCustomerId(acme.getId());
        chat.setWorkspaceId(brand.getId());
        chat.setPhoneNumberId(number.getId());
        chat.setRemoteId("919811100009@c.us");
        String chatId = chats.save(chat).getId();
        assertThatThrownBy(() -> service.createForChat(me, brand.getId(), chatId, null, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("only WhatsApp Web numbers can import history");
        assertThat(imports.count()).isZero();
    }

    @Test
    void disabledNumberIsRejected() {
        number.setDisabled(new Disabled("someone", EnableLevel.BRAND, Instant.now()));
        mongo.save(number);
        assertThatThrownBy(() -> service.create(me, brand.getId(), number.getId(), all(null, null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("the number is disabled");
        assertThat(imports.count()).isZero();
    }

    @Test
    void createForChatUsesTheChatRemoteId() {
        WaWebChat chat = new WaWebChat();
        chat.setCustomerId(acme.getId());
        chat.setWorkspaceId(brand.getId());
        chat.setPhoneNumberId(number.getId());
        chat.setRemoteId("5555@lid");
        chat = chats.save(chat);
        var created = service.createForChat(me, brand.getId(), chat.getId(), null, null);
        assertThat(created.imp().getScope().kind()).isEqualTo(HistoryScopeKind.CHATS);
        assertThat(created.imp().getScope().remoteIds()).containsExactly("5555@lid");
        assertThat(items.findAll()).extracting(WaWebHistoryImportChat::getRemoteId).containsExactly("5555@lid");
    }

    @Test
    void cancelMarksInactiveAndReleasesRunningItems() {
        WaWebHistoryImport imp = service.create(me, brand.getId(), number.getId(),
                chatsOf(List.of("919811100002"), null)).imp();
        WaWebHistoryImportChat row = items.findAll().get(0);
        row.setStatus(HistoryChatStatus.RUNNING);
        items.save(row);
        WaWebHistoryImport cancelled = service.cancel(brand.getId(), imp.getId());
        assertThat(cancelled.getStatus()).isEqualTo(HistoryImportStatus.CANCELLED);
        assertThat(cancelled.isActive()).isFalse();
        assertThat(cancelled.getFinishedAt()).isNotNull();
        assertThat(items.findById(row.getId()).orElseThrow().getStatus()).isEqualTo(HistoryChatStatus.PENDING);
    }

    @Test
    void cancelCompletedIsConflict() {
        WaWebHistoryImport imp = service.create(me, brand.getId(), number.getId(), all(null, null)).imp();
        imp.setStatus(HistoryImportStatus.COMPLETED);
        imp.setActive(false);
        imports.save(imp);
        assertThatThrownBy(() -> service.cancel(brand.getId(), imp.getId()))
                .isInstanceOfSatisfying(CodedException.class, e -> assertThat(e.code()).isEqualTo("HISTORY_IMPORT_STATE"));
    }

    private WaWebChat chatOn(String remoteId) {
        WaWebChat chat = new WaWebChat();
        chat.setCustomerId(acme.getId());
        chat.setWorkspaceId(brand.getId());
        chat.setPhoneNumberId(number.getId());
        chat.setRemoteId(remoteId);
        return chats.save(chat);
    }

    @Test
    void activeForChatFindsAPausedImportOfTheChat() {
        WaWebChat chat = chatOn("5555@lid");
        WaWebHistoryImport imp = service.createForChat(me, brand.getId(), chat.getId(), null, null).imp();
        service.pause(brand.getId(), imp.getId());
        assertThat(service.activeForChat(brand.getId(), chat.getId())).get()
                .extracting(WaWebHistoryImport::getId).isEqualTo(imp.getId());
    }

    @Test
    void activeForChatIgnoresStoppedImportsAndOtherChats() {
        WaWebChat chat = chatOn("5555@lid");
        WaWebChat otherChat = chatOn("6666@lid");
        WaWebHistoryImport imp = service.createForChat(me, brand.getId(), chat.getId(), null, null).imp();
        assertThat(service.activeForChat(brand.getId(), otherChat.getId())).isEmpty();
        service.cancel(brand.getId(), imp.getId());
        assertThat(service.activeForChat(brand.getId(), chat.getId())).isEmpty();
    }

    @Test
    void activeForChatIgnoresAnImportOfAllChats() {
        WaWebChat chat = chatOn("5555@lid");
        service.create(me, brand.getId(), number.getId(), all(null, null));
        assertThat(service.activeForChat(brand.getId(), chat.getId())).isEmpty();
    }

    @Test
    void pauseOfQueuedPlaysBackToQueued() {
        WaWebHistoryImport imp = service.create(me, brand.getId(), number.getId(), all(null, null)).imp();
        WaWebHistoryImport paused = service.pause(brand.getId(), imp.getId());
        assertThat(paused.getStatus()).isEqualTo(HistoryImportStatus.PAUSED);
        assertThat(paused.isActive()).isTrue();
        assertThat(service.resume(brand.getId(), imp.getId()).getStatus()).isEqualTo(HistoryImportStatus.QUEUED);
    }

    @Test
    void pauseOfRunningPlaysBackToRunning() {
        WaWebHistoryImport imp = service.create(me, brand.getId(), number.getId(), all(null, null)).imp();
        imp.setStatus(HistoryImportStatus.RUNNING);
        imp.setStartedAt(Instant.now());
        imports.save(imp);
        service.pause(brand.getId(), imp.getId());
        assertThat(service.resume(brand.getId(), imp.getId()).getStatus()).isEqualTo(HistoryImportStatus.RUNNING);
    }

    @Test
    void pausedImportCanBeStopped() {
        WaWebHistoryImport imp = service.create(me, brand.getId(), number.getId(), all(null, null)).imp();
        service.pause(brand.getId(), imp.getId());
        WaWebHistoryImport stopped = service.cancel(brand.getId(), imp.getId());
        assertThat(stopped.getStatus()).isEqualTo(HistoryImportStatus.CANCELLED);
        assertThat(stopped.isActive()).isFalse();
    }

    @Test
    void pausedImportIsReturnedForAnIdenticalRequest() {
        WaWebHistoryImport imp = service.create(me, brand.getId(), number.getId(), all(null, null)).imp();
        service.pause(brand.getId(), imp.getId());
        HistoryImportService.Created again = service.create(me, brand.getId(), number.getId(), all(null, null));
        assertThat(again.created()).isFalse();
        assertThat(again.imp().getId()).isEqualTo(imp.getId());
    }

    @Test
    void pauseOrPlayInTheWrongStateIsConflict() {
        WaWebHistoryImport imp = service.create(me, brand.getId(), number.getId(), all(null, null)).imp();
        assertThatThrownBy(() -> service.resume(brand.getId(), imp.getId()))
                .isInstanceOfSatisfying(CodedException.class, e -> assertThat(e.code()).isEqualTo("HISTORY_IMPORT_STATE"));
        service.cancel(brand.getId(), imp.getId());
        assertThatThrownBy(() -> service.pause(brand.getId(), imp.getId()))
                .isInstanceOfSatisfying(CodedException.class, e -> assertThat(e.code()).isEqualTo("HISTORY_IMPORT_STATE"));
    }

    @Test
    void listFiltersByNumberAndStatus() {
        service.create(me, brand.getId(), number.getId(), all(null, null));
        WaWebHistoryImport b = service.create(me, brand.getId(), other.getId(), all(null, null)).imp();
        b.setStatus(HistoryImportStatus.RUNNING);
        imports.save(b);
        assertThat(service.list(brand.getId(), null, null, 0).total()).isEqualTo(2);
        var byNumber = service.list(brand.getId(), other.getId(), null, 0);
        assertThat(byNumber.items()).extracting(HistoryImportView::id).containsExactly(b.getId());
        assertThat(service.list(brand.getId(), null, HistoryImportStatus.QUEUED, 0).total()).isEqualTo(1);
        assertThat(service.list(brand.getId(), number.getId(), HistoryImportStatus.RUNNING, 0).total()).isZero();
    }

    @Test
    void counterRefreshSumsItems() {
        WaWebHistoryImport imp = service.create(me, brand.getId(), number.getId(), all(null, null)).imp();
        item(imp, "1@c.us", HistoryChatStatus.DONE, new WaWebHistoryImportChat.Counts(5, 3, 1));
        item(imp, "2@c.us", HistoryChatStatus.SKIPPED, WaWebHistoryImportChat.Counts.ZERO);
        item(imp, "3@c.us", HistoryChatStatus.PENDING, WaWebHistoryImportChat.Counts.ZERO);
        item(imp, "4@c.us", HistoryChatStatus.FAILED, null);
        var counts = counter.refresh(imp.getId());
        assertThat(counts).isEqualTo(new WaWebHistoryImport.Counts(4, 1, 1, 1, 5, 3, 1));
        assertThat(imports.findById(imp.getId()).orElseThrow().getCounts()).isEqualTo(counts);
    }

    @Test
    void viewProgressPercent() {
        WaWebHistoryImport imp = service.create(me, brand.getId(), number.getId(), all(null, null)).imp();
        assertThat(views.view(imp).progressPercent()).isNull();
        item(imp, "1@c.us", HistoryChatStatus.DONE, WaWebHistoryImportChat.Counts.ZERO);
        item(imp, "2@c.us", HistoryChatStatus.FAILED, WaWebHistoryImportChat.Counts.ZERO);
        item(imp, "3@c.us", HistoryChatStatus.PENDING, WaWebHistoryImportChat.Counts.ZERO);
        imp.setCounts(counter.refresh(imp.getId()));
        assertThat(views.view(imp).progressPercent()).isEqualTo(66);
        assertThat(views.view(imp).phoneNumber().number()).isEqualTo(number.getNumber());
    }
}
