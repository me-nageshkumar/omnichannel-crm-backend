package me.nageshk.omnichannel.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.contact.DoNotContact;
import me.nageshk.omnichannel.events.WhatsAppEvent;
import me.nageshk.omnichannel.inbox.ChatStatus;
import me.nageshk.omnichannel.inbox.InboxEvents;
import me.nageshk.omnichannel.inbox.MessageDirection;
import me.nageshk.omnichannel.inbox.MessageSource;
import me.nageshk.omnichannel.inbox.MessageStatus;
import me.nageshk.omnichannel.inbox.WaWebChat;
import me.nageshk.omnichannel.inbox.WaWebMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class CampaignRepliesIT extends CampaignTestBase {

    private static final String PHONE = "919811100001";
    private static final String REMOTE = PHONE + "@c.us";
    private static final String OUT_ID = "true_919811100001@c.us_1";

    @Autowired ApplicationEventPublisher publisher;
    @Autowired JsonMapper mapper;
    @MockitoSpyBean CampaignCounter counter;
    @Autowired CampaignSettingsService settings;

    private final AtomicInteger seq = new AtomicInteger();
    private Campaign first;
    private WaWebChat chat;

    @BeforeEach
    void sent() {
        first = running("first");
        Contact c = contact("Asha", PHONE);
        chat = new WaWebChat();
        chat.setCustomerId(acme.getId());
        chat.setWorkspaceId(brand.getId());
        chat.setPhoneNumberId(n1.getId());
        chat.setRemoteId(REMOTE);
        chat.setContactId(c.getId());
        chat.setStatus(ChatStatus.OPEN);
        chat.setCampaignId(first.getId());
        chat = mongo.save(chat);
        WaWebMessage m = outMessage(OUT_ID, MessageSource.CAMPAIGN, first.getId(), WED_11_IST);
        CampaignRecipient r = newRecipient(first.getId(), PHONE);
        r.setStatus(RecipientStatus.SENT);
        r.setChatId(chat.getId());
        r.setMessageId(m.getId());
        r.setContactId(c.getId());
        r.setSentAt(WED_11_IST);
        mongo.save(r);
        counter.refresh(first.getId());
    }

    private Campaign running(String name) {
        Campaign c = new Campaign();
        c.setWorkspaceId(brand.getId());
        c.setCustomerId(acme.getId());
        c.setName(name);
        c.setNameKey(name);
        c.setStatus(CampaignStatus.RUNNING);
        return mongo.save(c);
    }

    private WaWebMessage outMessage(String waId, MessageSource source, String campaignId, java.time.Instant at) {
        WaWebMessage m = new WaWebMessage();
        m.setCustomerId(acme.getId());
        m.setWorkspaceId(brand.getId());
        m.setPhoneNumberId(n1.getId());
        m.setChatId(chat.getId());
        m.setWaMessageId(waId);
        m.setDirection(MessageDirection.OUT);
        m.setSource(source);
        m.setType("chat");
        m.setBody("Hi");
        m.setTimestamp(at);
        m.setStatus(MessageStatus.SENT);
        m.setCampaignId(campaignId);
        return mongo.save(m);
    }

    private void publish(String name, ObjectNode payload) {
        publisher.publishEvent(new WhatsAppEvent(this, n1.getId(), brand.getId(), acme.getId(), name, payload, null,
                null));
    }

    private void ack(String waMessageId, int ack) {
        ObjectNode p = mapper.createObjectNode();
        p.putObject("message").putObject("id").put("_serialized", waMessageId);
        p.put("ack", ack);
        publish("message_ack", p);
    }

    private void inbound(String remoteId, String body) {
        ObjectNode p = mapper.createObjectNode();
        ObjectNode id = p.putObject("id");
        id.put("fromMe", false);
        id.put("remote", remoteId);
        id.put("id", "IN" + seq.incrementAndGet());
        p.put("fromMe", false);
        p.put("timestamp", clock.instant().getEpochSecond());
        p.put("body", body);
        publish("message_create", p);
    }

    private CampaignRecipient recipient() {
        return recipient(first.getId(), PHONE);
    }

    private WaWebChat chat() {
        return mongo.findById(chat.getId(), WaWebChat.class);
    }

    @Test
    void acksMoveTheRecipient() {
        ack(OUT_ID, 2);
        assertThat(recipient().getStatus()).isEqualTo(RecipientStatus.DELIVERED);
        assertThat(recipient().getDeliveredAt()).isEqualTo(WED_11_IST);
        clock.set(WED_11_IST.plusSeconds(60));
        ack(OUT_ID, 3);
        CampaignRecipient r = recipient();
        assertThat(r.getStatus()).isEqualTo(RecipientStatus.READ);
        assertThat(r.getReadAt()).isEqualTo(WED_11_IST.plusSeconds(60));
        assertThat(r.getDeliveredAt()).isEqualTo(WED_11_IST);
        Campaign.Counts c = campaign(first.getId()).getCounts();
        assertThat(c.sent()).isEqualTo(1);
        assertThat(c.delivered()).isEqualTo(1);
        assertThat(c.read()).isEqualTo(1);
    }

    @Test
    void readFromSentAlsoSetsDelivered() {
        ack(OUT_ID, 3);
        assertThat(recipient().getStatus()).isEqualTo(RecipientStatus.READ);
        assertThat(recipient().getDeliveredAt()).isEqualTo(WED_11_IST);
    }

    @Test
    void playedCountsAsRead() {
        ack(OUT_ID, 4);
        assertThat(recipient().getStatus()).isEqualTo(RecipientStatus.READ);
        assertThat(recipient().getReadAt()).isNotNull();
    }

    @Test
    void replyIsRecordedOnce() {
        inbound(REMOTE, "hi");
        assertThat(recipient().getRepliedAt()).isEqualTo(WED_11_IST);
        assertThat(campaign(first.getId()).getCounts().replied()).isEqualTo(1);
        clock.set(WED_11_IST.plus(Duration.ofHours(1)));
        inbound(REMOTE, "hello again");
        assertThat(recipient().getRepliedAt()).isEqualTo(WED_11_IST);
    }

    @Test
    void stopWordMarksTheContactAndSkipsPendingEverywhere() {
        Campaign second = running("second");
        mongo.save(newRecipient(second.getId(), PHONE));
        counter.refresh(second.getId());
        inbound(REMOTE, "  Don't Message ");
        Contact c = mongo.findOne(new Query(Criteria.where("workspaceId").is(brand.getId()).and("phone").is(PHONE)),
                Contact.class);
        assertThat(c.getDoNotContact().reason()).isEqualTo(DoNotContact.Reason.STOP_REPLY);
        assertThat(c.getDoNotContact().campaignId()).isEqualTo(first.getId());
        assertThat(recipient(second.getId(), PHONE).getSkipReason()).isEqualTo(SkipReason.DO_NOT_CONTACT);
        assertThat(recipient(second.getId(), PHONE).getStatus()).isEqualTo(RecipientStatus.SKIPPED);
        assertThat(campaign(second.getId()).getCounts().skipped()).isEqualTo(1);
        assertThat(recipient().getRepliedAt()).isNotNull();
        assertThat(chat().getStatus()).isEqualTo(ChatStatus.OPEN);
    }

    @Test
    void stopAfterAnAgentWroteIsAnAnswer() {
        outMessage("true_919811100001@c.us_2", MessageSource.CONSOLE, null, WED_11_IST.plusSeconds(30));
        clock.set(WED_11_IST.plusSeconds(60));
        inbound(REMOTE, "no");
        Contact c = mongo.findOne(new Query(Criteria.where("workspaceId").is(brand.getId()).and("phone").is(PHONE)),
                Contact.class);
        assertThat(c.getDoNotContact()).isNull();
        assertThat(recipient().getRepliedAt()).isEqualTo(WED_11_IST.plusSeconds(60));
    }

    @Test
    void stopWordsComeFromSettings() {
        CampaignSettings s = settings.get(brand.getId());
        s.setStopWords(List.of("nahi"));
        mongo.save(s);
        inbound(REMOTE, "stop");
        assertThat(contactOf().getDoNotContact()).isNull();
        inbound(REMOTE, "Nahi");
        assertThat(contactOf().getDoNotContact()).isNotNull();
    }

    private Contact contactOf() {
        return mongo.findOne(new Query(Criteria.where("workspaceId").is(brand.getId()).and("phone").is(PHONE)),
                Contact.class);
    }

    @Test
    void plainChatsAreIgnored() {
        mongo.remove(new Query(), CampaignRecipient.class);
        mongo.updateFirst(new Query(Criteria.where("_id").is(chat.getId())),
                new org.springframework.data.mongodb.core.query.Update().unset("campaignId"), WaWebChat.class);
        mongo.remove(new Query(Criteria.where("source").is(MessageSource.CAMPAIGN)), WaWebMessage.class);
        outMessage("true_919811100001@c.us_3", MessageSource.CONSOLE, null, WED_11_IST);
        inbound(REMOTE, "stop");
        assertThat(contactOf().getDoNotContact()).isNull();
        assertThat(mongo.count(new Query(), CampaignRecipient.class)).isZero();
    }

    @Test
    void listenerFailureDoesNotBreakIngestion() {
        doThrow(new IllegalStateException("boom")).when(counter).refresh(anyString());
        Recorder.events.clear();
        inbound(REMOTE, "hi");
        assertThat(mongo.count(new Query(Criteria.where("direction").is(MessageDirection.IN)), WaWebMessage.class))
                .isEqualTo(1);
        assertThat(Recorder.events).isNotEmpty();
    }

    /** Collects the {@code ChatChanged} events published after the failing listener ran. */
    @org.springframework.boot.test.context.TestConfiguration
    static class Recorder {
        static final List<InboxEvents.ChatChanged> events = new ArrayList<>();

        @EventListener
        void on(InboxEvents.ChatChanged e) {
            events.add(e);
        }
    }
}
