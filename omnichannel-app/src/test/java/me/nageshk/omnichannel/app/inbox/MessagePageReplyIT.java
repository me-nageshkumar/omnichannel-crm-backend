package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import me.nageshk.omnichannel.app.support.MutableClock;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.page.MessagePage;
import me.nageshk.omnichannel.db.page.MessagePage.ActionKind;
import me.nageshk.omnichannel.db.page.MessagePageRepository;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessages;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import me.nageshk.omnichannel.service.page.MessagePageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** Spec §5.7: a customer's WhatsApp reply is matched to the button or row they chose, within the window. */
@SpringBootTest
@Import(MessagePageReplyIT.Fixtures.class)
class MessagePageReplyIT extends InboxApiTestBase {

    @TestConfiguration
    static class Fixtures {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired MessagePageService pages;
    @Autowired MessagePageRepository repo;
    @Autowired CloudMessages cloud;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(T0);
    }

    MessagePage page(WaWebChat chat, String json) throws Exception {
        return pages.drawPreview(pages.create(chat, cloud.parse(mapper.readTree(json))));
    }

    WaWebMessage inbound(WaWebChat chat, long seconds, String body) {
        WaWebMessage m = message(chat, seconds, body);
        publisher.publishEvent(new InboxEvents.MessageUpserted(chat, m, true));
        return m;
    }

    WaWebMessage inboundLocation(WaWebChat chat, long seconds, double lat, double lng) {
        WaWebMessage m = message(chat, seconds, null);
        m.setType("location");
        m.setLocation(new WaWebMessage.Location(lat, lng, "Here"));
        m = messages.save(m);
        publisher.publishEvent(new InboxEvents.MessageUpserted(chat, m, true));
        return m;
    }

    MessagePage reload(MessagePage p) {
        return repo.findById(p.getId()).orElseThrow();
    }

    @Test
    void matchesATypedButtonTitle() throws Exception {
        WaWebChat chat = chat("Asha", "919900000101", 1);
        MessagePage p = page(chat, MessagePageIT.BUTTON);
        WaWebMessage in = inbound(chat, 10, " change! ");
        MessagePage after = reload(p);
        assertThat(after.getActions()).hasSize(1);
        MessagePage.Action a = after.getActions().get(0);
        assertThat(a.kind()).isEqualTo(ActionKind.BUTTON_REPLY);
        assertThat(a.id()).isEqualTo("change-button");
        assertThat(a.title()).isEqualTo("Change");
        assertThat(a.repliedMessageId()).isEqualTo(in.getId());
        assertThat(pages.view(after).buttons().get(0).replied()).isTrue();
        assertThat(pages.view(after).buttons().get(0).count()).isEqualTo(1);
    }

    @Test
    void completesATappedActionInstead() throws Exception {
        WaWebChat chat = chat("Asha", "919900000102", 1);
        MessagePage p = page(chat, MessagePageIT.BUTTON);
        pages.reply(p, "change-button", "ua");
        WaWebMessage in = inbound(chat, 10, "Change");
        MessagePage after = reload(p);
        assertThat(after.getActions()).hasSize(1);
        assertThat(after.getActions().get(0).repliedMessageId()).isEqualTo(in.getId());
    }

    @Test
    void matchesAListRowTitle() throws Exception {
        WaWebChat chat = chat("Asha", "919900000103", 1);
        String list = """
                {"messaging_product":"whatsapp-web","to":"919900000103","type":"interactive",
                 "interactive":{"type":"list","body":{"text":"Pick"},"action":{"button":"Options","sections":[
                   {"title":"Fast","rows":[{"id":"overnight","title":"Overnight Delivery"},{"id":"two_day","title":"Two-Day Delivery"}]}]}}}""";
        MessagePage p = page(chat, list);
        inbound(chat, 10, "two-day delivery");
        MessagePage after = reload(p);
        assertThat(after.getActions()).extracting(MessagePage.Action::kind).containsExactly(ActionKind.LIST_REPLY);
        assertThat(after.getActions().get(0).id()).isEqualTo("two_day");
        assertThat(pages.view(after).rows().get(1).replied()).isTrue();
    }

    @Test
    void ignoresOtherText() throws Exception {
        WaWebChat chat = chat("Asha", "919900000104", 1);
        MessagePage p = page(chat, MessagePageIT.BUTTON);
        inbound(chat, 10, "hello there");
        assertThat(reload(p).getActions()).isEmpty();
    }

    @Test
    void ignoresRepliesOutsideTheWindow() throws Exception {
        WaWebChat chat = chat("Asha", "919900000105", 1);
        MessagePage p = page(chat, MessagePageIT.BUTTON);
        clock.advance(Duration.ofHours(49));
        inbound(chat, 49 * 3600, "Change");
        assertThat(reload(p).getActions()).isEmpty();
    }

    @Test
    void ignoresRepliesOlderThanThePage() throws Exception {
        WaWebChat chat = chat("Asha", "919900000106", 1);
        clock.advance(Duration.ofMinutes(10));
        MessagePage p = page(chat, MessagePageIT.BUTTON);
        inbound(chat, 5, "Change");
        assertThat(reload(p).getActions()).isEmpty();
    }

    @Test
    void inboundLocationClosesALocationRequest() throws Exception {
        WaWebChat chat = chat("Asha", "919900000107", 1);
        MessagePage p = page(chat, MessagePageIT.LOCATION);
        WaWebMessage in = inboundLocation(chat, 10, 12.97, 77.59);
        MessagePage after = reload(p);
        assertThat(after.getActions()).hasSize(1);
        MessagePage.Action a = after.getActions().get(0);
        assertThat(a.kind()).isEqualTo(ActionKind.LOCATION);
        assertThat(a.latitude()).isEqualTo(12.97);
        assertThat(a.repliedMessageId()).isEqualTo(in.getId());
        assertThat(pages.view(after).location().longitude()).isEqualTo(77.59);
    }

    @Test
    void newestPageWins() throws Exception {
        WaWebChat chat = chat("Asha", "919900000108", 1);
        MessagePage older = page(chat, MessagePageIT.BUTTON);
        clock.advance(Duration.ofMinutes(1));
        MessagePage newer = page(chat, MessagePageIT.BUTTON);
        inbound(chat, 120, "Cancel");
        assertThat(reload(older).getActions()).isEmpty();
        assertThat(reload(newer).getActions()).extracting(MessagePage.Action::id).containsExactly("cancel-button");
    }
}
