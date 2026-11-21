package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import me.nageshk.omnichannel.app.automation.AutomationTestBase;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.node.ObjectNode;

/** A WhatsApp Web reply keeps what it quotes, and a forward says so (spec 2026-11-21 reply quotes). */
class ReplyQuotesIT extends AutomationTestBase {

    private static final String REMOTE = "919811122233@c.us";

    private WaWebMessage stored(String body) {
        return mongo.findOne(new Query(Criteria.where("body").is(body)), WaWebMessage.class);
    }

    /** Our own message in the chat with the stanza id {@code stanza}. */
    private void ours(String remote, String stanza, String body) {
        ObjectNode p = payload(remote, true, "chat", body, null);
        ((ObjectNode) p.get("id")).put("_serialized", "true_" + remote + "_" + stanza).put("id", stanza);
        publish(n1, p);
    }

    private ObjectNode reply(String body, String stanza) {
        ObjectNode p = payload(REMOTE, false, "chat", body, "Asha");
        p.put("quotedStanzaID", stanza);
        p.putObject("quotedParticipant").put("server", "c.us").put("user", n1.getNumber())
                .put("_serialized", n1.getNumber() + "@c.us");
        return p;
    }

    @Test
    void waWebReplyQuotesStoredMessage() {
        ours(REMOTE, "3B5D", "Fees are due on Friday");
        publish(n1, reply("Okay sir", "3B5D"));
        WaWebMessage.Quoted q = stored("Okay sir").getQuoted();
        assertThat(q.messageId()).isEqualTo(stored("Fees are due on Friday").getId());
        assertThat(q.fromMe()).isTrue();
        assertThat(q.text()).isEqualTo("Fees are due on Friday");
        assertThat(q.type()).isEqualTo("text");
    }

    @Test
    void unknownWaWebQuoteUsesItsText() {
        ObjectNode p = reply("Okay sir", "9ZZZ");
        p.putObject("quotedMsg").put("type", "chat").put("body", "Good morning Mam");
        publish(n1, p);
        WaWebMessage.Quoted q = stored("Okay sir").getQuoted();
        assertThat(q.messageId()).isNull();
        assertThat(q.waMessageId()).isEqualTo("9ZZZ");
        assertThat(q.text()).isEqualTo("Good morning Mam");
        assertThat(q.type()).isEqualTo("text");
        assertThat(q.fromMe()).isTrue();
    }

    @Test
    void unknownQuoteOfTheirsIsTheirs() {
        ObjectNode p = payload(REMOTE, false, "chat", "Okay sir", "Asha");
        p.put("quotedStanzaID", "9YYY");
        p.putObject("quotedParticipant").put("server", "c.us").put("user", "919811122233")
                .put("_serialized", "919811122233@c.us");
        p.putObject("quotedMsg").put("type", "chat").put("body", "When is the exam?");
        publish(n1, p);
        assertThat(stored("Okay sir").getQuoted().fromMe()).isFalse();
    }

    @Test
    void quoteResolvesWhenTheOriginalArrivesLater() {
        publish(n1, reply("Okay sir", "3B5D"));
        assertThat(stored("Okay sir").getQuoted().messageId()).isNull();
        ours(REMOTE, "3B5D", "Fees are due on Friday");
        WaWebMessage.Quoted q = stored("Okay sir").getQuoted();
        assertThat(q.messageId()).isEqualTo(stored("Fees are due on Friday").getId());
        assertThat(q.fromMe()).isTrue();
        assertThat(q.text()).isEqualTo("Fees are due on Friday");
    }

    @Test
    void forwardedIsMarked() {
        ObjectNode p = payload(REMOTE, false, "chat", "Look at this", "Asha");
        p.put("isForwarded", true);
        publish(n1, p);
        assertThat(stored("Look at this").getForwarded()).isTrue();
        assertThat(stored("Look at this").getQuoted()).isNull();
    }

    @Test
    void quoteFromAnotherChatIsNotUsed() {
        ours("919800000099@c.us", "3B5D", "Someone else's message");
        publish(n1, reply("Okay sir", "3B5D"));
        assertThat(stored("Okay sir").getQuoted().messageId()).isNull();
    }

    @Test
    void reIngestKeepsTheQuote() {
        ours(REMOTE, "3B5D", "Fees are due on Friday");
        ObjectNode p = reply("Okay sir", "3B5D");
        publish(n1, p);
        publish(n1, p.deepCopy());
        assertThat(mongo.count(new Query(Criteria.where("body").is("Okay sir")), WaWebMessage.class)).isEqualTo(1);
        assertThat(stored("Okay sir").getQuoted().messageId()).isEqualTo(stored("Fees are due on Friday").getId());
    }

    @Test
    void stanzaSuffixMustBeWhole() {
        ours(REMOTE, "X3B5D", "A different message");
        publish(n1, reply("Okay sir", "3B5D"));
        assertThat(stored("Okay sir").getQuoted().messageId()).isNull();
    }

    @Test
    void longQuoteIsCut() {
        ObjectNode p = reply("Okay sir", "9ZZZ");
        p.putObject("quotedMsg").put("type", "chat").put("body", "x".repeat(500));
        publish(n1, p);
        assertThat(stored("Okay sir").getQuoted().text()).hasSize(300);
    }

    @Test
    void ourPhoneReplyKeepsItsQuoteToo() {
        publish(n1, payload(REMOTE, false, "chat", "Is the bus at 8?", "Asha"));
        WaWebChat chat = chats.findByPhoneNumberIdAndRemoteId(n1.getId(), REMOTE).orElseThrow();
        String theirs = stored("Is the bus at 8?").getWaMessageId();
        String stanza = theirs.substring(theirs.lastIndexOf('_') + 1);
        ObjectNode p = payload(REMOTE, true, "chat", "Yes, at 8", null);
        p.put("quotedStanzaID", stanza);
        publish(n1, p);
        WaWebMessage.Quoted q = stored("Yes, at 8").getQuoted();
        assertThat(q.messageId()).isEqualTo(stored("Is the bus at 8?").getId());
        assertThat(q.fromMe()).isFalse();
        assertThat(chat).isNotNull();
    }
}
