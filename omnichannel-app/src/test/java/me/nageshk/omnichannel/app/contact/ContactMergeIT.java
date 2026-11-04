package me.nageshk.omnichannel.app.contact;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactNote;
import me.nageshk.omnichannel.db.contact.ContactSource;
import me.nageshk.omnichannel.db.contact.DoNotContact;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.service.contact.ContactEvents;
import me.nageshk.omnichannel.service.contact.ContactMergeService;
import me.nageshk.omnichannel.service.contact.ContactService;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Two contacts that are one person become one: every reference follows the survivor. */
@RecordApplicationEvents
class ContactMergeIT extends ContactApiTestBase {

    private static final String PHONE = "919812345678";
    private static final String LID = "123456789012345@lid";
    private static final String PN = "pn-merge";
    private static final Instant T1 = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-03T10:00:00Z");

    @Autowired ContactMergeService merges;
    @Autowired ContactService contactService;
    @Autowired WaWebChatRepository chats;
    @Autowired ApplicationEvents events;

    @AfterEach
    void removeChatsAndRecipients() {
        mongo.remove(new Query(), WaWebChat.class);
        mongo.remove(new Query(), CampaignRecipient.class);
    }

    private Contact saved(String name, boolean nameEdited, String phone, String... waIds) {
        Contact c = contact(brandA, name, phone);
        c.setNameEdited(nameEdited);
        c.setWaIds(new ArrayList<>(List.of(waIds)));
        return contacts.save(c);
    }

    private WaWebChat chat(String remoteId, Contact c) {
        WaWebChat chat = new WaWebChat();
        chat.setCustomerId(acme.getId());
        chat.setWorkspaceId(brandA.getId());
        chat.setPhoneNumberId(PN);
        chat.setRemoteId(remoteId);
        chat.setContactId(c.getId());
        chat.setStatus(ChatStatus.OPEN);
        return chats.save(chat);
    }

    private ContactNote note(Contact c, String text) {
        ContactNote n = new ContactNote();
        n.setWorkspaceId(brandA.getId());
        n.setContactId(c.getId());
        n.setText(text);
        return notes.save(n);
    }

    private CampaignRecipient recipient(Contact c, String phone) {
        CampaignRecipient r = new CampaignRecipient();
        r.setCampaignId("camp-1");
        r.setWorkspaceId(brandA.getId());
        r.setPhone(phone);
        r.setContactId(c.getId());
        r.setStatus(RecipientStatus.SENT);
        return mongo.insert(r);
    }

    private List<String> contactIdsOf(Class<?> type) {
        return mongo.find(new Query(Criteria.where("workspaceId").is(brandA.getId())), type).stream()
                .map(o -> o instanceof WaWebChat w ? w.getContactId()
                        : o instanceof ContactNote n ? n.getContactId() : ((CampaignRecipient) o).getContactId())
                .toList();
    }

    @Test
    void mergeMovesEveryReference() {
        var l1 = label(brandA, "VIP");
        var l2 = label(brandA, "Festive");
        var l3 = label(brandA, "Wholesale");
        var city = attribute(brandA, "City");
        var tier = attribute(brandA, "Tier");

        Contact survivor = saved("Asha", true, PHONE, PHONE + "@c.us");
        survivor.setLabels(Set.of(l1.getId(), l2.getId()));
        survivor.setAttributes(new java.util.HashMap<>(Map.of(city.getId(), "Pune")));
        survivor.setLastMessageAt(T1);
        survivor = contacts.save(survivor);

        Contact other = saved("Asha WA", false, null, LID);
        other.setSource(ContactSource.CHAT);
        other.setLabels(Set.of(l2.getId(), l3.getId()));
        other.setAttributes(new java.util.HashMap<>(Map.of(city.getId(), "Mumbai", tier.getId(), "Gold")));
        other.setLastMessageAt(T2);
        other.setDoNotContact(new DoNotContact(T1, DoNotContact.Reason.STOP_REPLY, "camp-1"));
        other = contacts.save(other);

        WaWebChat phoneChat = chat(PHONE + "@c.us", survivor);
        WaWebChat lidChat = chat(LID, other);
        note(survivor, "first");
        note(other, "second");
        recipient(survivor, PHONE);
        recipient(other, null);

        Contact merged = merges.merge(brandA.getId(), survivor.getId(), other.getId());

        Contact stored = contacts.findById(survivor.getId()).orElseThrow();
        assertThat(merged.getId()).isEqualTo(survivor.getId());
        assertThat(stored.getWaIds()).containsExactly(PHONE + "@c.us", LID);
        assertThat(stored.getPhone()).isEqualTo(PHONE);
        assertThat(stored.getName()).isEqualTo("Asha");
        assertThat(stored.getLabels()).containsExactlyInAnyOrder(l1.getId(), l2.getId(), l3.getId());
        assertThat(stored.getAttributes()).containsExactlyInAnyOrderEntriesOf(
                Map.of(city.getId(), "Pune", tier.getId(), "Gold"));
        assertThat(stored.getLastMessageAt()).isEqualTo(T2);
        assertThat(stored.getDoNotContact()).isNotNull();
        assertThat(stored.getDoNotContact().reason()).isEqualTo(DoNotContact.Reason.STOP_REPLY);
        assertThat(stored.getSource()).isEqualTo(ContactSource.MANUAL);
        assertThat(contacts.findById(other.getId())).isEmpty();

        assertThat(contactIdsOf(WaWebChat.class)).containsOnly(survivor.getId()).hasSize(2);
        assertThat(contactIdsOf(ContactNote.class)).containsOnly(survivor.getId()).hasSize(2);
        assertThat(contactIdsOf(CampaignRecipient.class)).containsOnly(survivor.getId()).hasSize(2);

        assertThat(events.stream(ContactEvents.ContactsMerged.class)).containsExactly(
                new ContactEvents.ContactsMerged(brandA.getId(), survivor.getId(), other.getId()));
        List<InboxEvents.ChatChanged> changed = events.stream(InboxEvents.ChatChanged.class).toList();
        assertThat(changed).extracting(e -> e.chat().getId())
                .containsExactlyInAnyOrder(phoneChat.getId(), lidChat.getId());
        assertThat(changed).extracting(e -> e.chat().getContactId()).containsOnly(stored.getId());
    }

    @Test
    void survivorHasThePhoneElseIsOlder() {
        Contact older = new Contact();
        older.setId("older");
        older.setCreationTime(T1);
        Contact newer = new Contact();
        newer.setId("newer");
        newer.setCreationTime(T2);

        assertThat(ContactMergeService.survivorOf(older, newer)).isEqualTo("older");
        assertThat(ContactMergeService.survivorOf(newer, older)).isEqualTo("older");
        newer.setPhone(PHONE);
        assertThat(ContactMergeService.survivorOf(older, newer)).isEqualTo("newer");
        assertThat(ContactMergeService.survivorOf(newer, older)).isEqualTo("newer");
    }

    @Test
    void nameComesFromTheOtherWhenTheSurvivorsIsBlankOrNotEdited() {
        Contact survivor = saved("from WhatsApp", false, PHONE, PHONE + "@c.us");
        Contact other = saved("Asha Rao", true, null, LID);

        merges.merge(brandA.getId(), survivor.getId(), other.getId());

        Contact stored = contacts.findById(survivor.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("Asha Rao");
        assertThat(stored.isNameEdited()).isTrue();

        Contact blank = saved(null, false, "919811111111", "919811111111@c.us");
        Contact named = saved("Ravi", false, null, "555@lid");
        merges.merge(brandA.getId(), blank.getId(), named.getId());
        assertThat(contacts.findById(blank.getId()).orElseThrow().getName()).isEqualTo("Ravi");
    }

    @Test
    void mergeIsIdempotent() {
        Contact survivor = saved("Asha", true, PHONE, PHONE + "@c.us");
        Contact other = saved("Asha WA", false, null, LID);
        chat(LID, other);
        note(other, "kept");

        merges.merge(brandA.getId(), survivor.getId(), other.getId());
        Contact once = contacts.findById(survivor.getId()).orElseThrow();
        long changedOnce = events.stream(InboxEvents.ChatChanged.class).count();

        Contact again = merges.merge(brandA.getId(), survivor.getId(), other.getId());

        Contact twice = contacts.findById(survivor.getId()).orElseThrow();
        assertThat(again.getId()).isEqualTo(survivor.getId());
        assertThat(twice.getVersion()).isEqualTo(once.getVersion());
        assertThat(twice.getWaIds()).containsExactly(PHONE + "@c.us", LID);
        assertThat(contacts.count()).isEqualTo(1);
        assertThat(contactIdsOf(WaWebChat.class)).containsExactly(survivor.getId());
        assertThat(contactIdsOf(ContactNote.class)).containsExactly(survivor.getId());
        assertThat(events.stream(ContactEvents.ContactsMerged.class)).hasSize(1);
        assertThat(events.stream(InboxEvents.ChatChanged.class).count()).isEqualTo(changedOnce);
    }

    @Test
    void consoleFoldMergesANamedLidContact() {
        var vip = label(brandA, "VIP");
        Contact byPhone = saved("Asha", true, PHONE, PHONE + "@c.us");
        Contact byLid = saved("Asha WA", false, null, LID);
        byLid.setLabels(Set.of(vip.getId()));
        byLid = contacts.save(byLid);
        WaWebChat lidChat = chat(LID, byLid);
        note(byLid, "from the lid chat");

        Contact linked = contactService.linkLidToPhone(brandA.getId(), acme.getId(), LID, PHONE, T2);

        assertThat(linked.getId()).isEqualTo(byPhone.getId());
        assertThat(contacts.findById(byLid.getId())).isEmpty();
        Contact stored = contacts.findById(byPhone.getId()).orElseThrow();
        assertThat(stored.getWaIds()).containsExactly(PHONE + "@c.us", LID);
        assertThat(stored.getName()).isEqualTo("Asha");
        assertThat(stored.getLabels()).containsExactly(vip.getId());
        assertThat(stored.getLastMessageAt()).isEqualTo(T2);
        assertThat(chats.findById(lidChat.getId()).orElseThrow().getContactId()).isEqualTo(byPhone.getId());
        assertThat(contactIdsOf(ContactNote.class)).containsExactly(byPhone.getId());
        assertThat(events.stream(ContactEvents.ContactsMerged.class)).hasSize(1);
    }
}
