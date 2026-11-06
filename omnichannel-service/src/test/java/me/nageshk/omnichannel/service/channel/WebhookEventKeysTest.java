package me.nageshk.omnichannel.service.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.ChannelKind;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Change;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Message;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.MessageEcho;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.PollVote;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Status;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Value;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WebhookEvent;
import org.junit.jupiter.api.Test;

class WebhookEventKeysTest {

    private static final ChannelContext CTX =
            new ChannelContext(ChannelKind.WHATSAPP_WEB, "c1", "w1", "p1", "919928088743");

    private static WebhookEvent event(String field, Value value) {
        return WebhookEvent.of(CTX, List.of(new Change(field, value)));
    }

    private static Message text(String id) {
        return Message.text(id, "919811100001", null, "1700000000", "hi", null);
    }

    private static MessageEcho echo(String id, String type, PollVote vote) {
        return new MessageEcho("919928088743", "919811100001", id, "1700000100", type, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, vote, null, null, null, null, null, null);
    }

    @Test
    void aLiveMessageOrEchoIsKeyedByItsId() {
        assertThat(WebhookEventKeys.of(event("messages", Value.of(CTX).withMessages(List.of(text("m1")))), false))
                .isEqualTo("message:m1");
        assertThat(WebhookEventKeys.of(event("smb_message_echoes",
                Value.of(CTX).withMessageEchoes(List.of(echo("e1", "text", null)))), false))
                .isEqualTo("message:e1");
    }

    @Test
    void anImportedMessageOrEchoIsKeyedAsHistory() {
        assertThat(WebhookEventKeys.of(event("messages", Value.of(CTX).withMessages(List.of(text("m1")))), true))
                .isEqualTo("history:m1");
        assertThat(WebhookEventKeys.of(event("smb_message_echoes",
                Value.of(CTX).withMessageEchoes(List.of(echo("e1", "text", null)))), true))
                .isEqualTo("history:e1");
    }

    @Test
    void aStatusIsKeyedByItsMessageAndState() {
        Status read = new Status("e1", "read", "1700000200", "919811100001", null, null, null, null, null, null);
        assertThat(WebhookEventKeys.of(event("messages", Value.of(CTX).withStatuses(List.of(read))), false))
                .isEqualTo("status:e1:read");
    }

    @Test
    void aPollVoteIsKeyedByItsIdAndTime() {
        Message vote = new Message("919811100001", null, "vote:poll1:919811100001@c.us", "1700000300", "poll_vote",
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                new PollVote("poll1", List.of(0)), null, null, null, null, null, null);
        assertThat(WebhookEventKeys.of(event("messages", Value.of(CTX).withMessages(List.of(vote))), false))
                .isEqualTo("vote:vote:poll1:919811100001@c.us:1700000300");
        MessageEcho ours = echo("vote:poll1:919928088743@c.us", "poll_vote", new PollVote("poll1", List.of(1)));
        assertThat(WebhookEventKeys.of(event("smb_message_echoes",
                Value.of(CTX).withMessageEchoes(List.of(ours))), false))
                .isEqualTo("vote:vote:poll1:919928088743@c.us:1700000100");
    }

    @Test
    void aShellHasNoKey() {
        assertThat(WebhookEventKeys.of(event("messages", Value.of(CTX)), false)).isNull();
        assertThat(WebhookEventKeys.of(event(null, null), false)).isNull();
        assertThat(WebhookEventKeys.of(WebhookEvent.of(CTX, List.of()), false)).isNull();
        assertThat(WebhookEventKeys.of(null, false)).isNull();
    }

    @Test
    void theTimeIsTheMessagesOrStatusesTimestamp() {
        assertThat(WebhookEventKeys.secondsOf(event("messages", Value.of(CTX).withMessages(List.of(text("m1"))))))
                .isEqualTo(1_700_000_000L);
        Status read = new Status("e1", "read", "1700000200", "919811100001", null, null, null, null, null, null);
        assertThat(WebhookEventKeys.secondsOf(event("messages", Value.of(CTX).withStatuses(List.of(read)))))
                .isEqualTo(1_700_000_200L);
        Status bad = new Status("e1", "read", "soon", "919811100001", null, null, null, null, null, null);
        assertThat(WebhookEventKeys.secondsOf(event("messages", Value.of(CTX).withStatuses(List.of(bad))))).isNull();
        assertThat(WebhookEventKeys.secondsOf(event("messages", Value.of(CTX)))).isNull();
    }
}
