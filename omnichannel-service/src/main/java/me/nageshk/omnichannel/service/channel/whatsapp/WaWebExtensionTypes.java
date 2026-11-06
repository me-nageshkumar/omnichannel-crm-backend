package me.nageshk.omnichannel.service.channel.whatsapp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Album;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.BizContentPlaceholder;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Button;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.CallLog;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Change;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Interactive;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Message;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.MessageEcho;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.MessageError;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.NewsletterFollowerInvite;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Order;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.PollCreation;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.PollVote;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Revoked;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Text;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.Value;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WaWeb;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WebhookEvent;
import me.nageshk.omnichannel.service.inbox.PollVotes;
import tools.jackson.databind.JsonNode;

/** The raw types that are not Meta's own (polls, calls, revocations, ...), the business ones and poll votes. Pure. */
final class WaWebExtensionTypes {

    private static final int UNKNOWN_CODE = 131051;
    private static final String UNKNOWN_TITLE = "Message type unknown";

    private WaWebExtensionTypes() {
    }

    /** The type and the one type object of a raw message that is not a core type. */
    record Mapped(String type, List<MessageError> errors, Text text, Button button, Interactive interactive,
            Order order, PollCreation pollCreation, CallLog callLog, Revoked revoked,
            BizContentPlaceholder bizContentPlaceholder, NewsletterFollowerInvite newsletterFollowerInvite,
            Album album, boolean media) {

        static final Mapped NONE = new Mapped(null, null, null, null, null, null, null, null, null, null, null, null,
                false);

        private Mapped withType(String t) {
            return new Mapped(t, errors, text, button, interactive, order, pollCreation, callLog, revoked,
                    bizContentPlaceholder, newsletterFollowerInvite, album, media);
        }

        Mapped with(PollCreation v) {
            return new Mapped(type, errors, text, button, interactive, order, v, callLog, revoked,
                    bizContentPlaceholder, newsletterFollowerInvite, album, media);
        }

        Mapped with(CallLog v) {
            return new Mapped(type, errors, text, button, interactive, order, pollCreation, v, revoked,
                    bizContentPlaceholder, newsletterFollowerInvite, album, media);
        }

        Mapped with(Revoked v) {
            return new Mapped(type, errors, text, button, interactive, order, pollCreation, callLog, v,
                    bizContentPlaceholder, newsletterFollowerInvite, album, media);
        }

        Mapped with(BizContentPlaceholder v) {
            return new Mapped(type, errors, text, button, interactive, order, pollCreation, callLog, revoked, v,
                    newsletterFollowerInvite, album, media);
        }

        Mapped with(NewsletterFollowerInvite v) {
            return new Mapped(type, errors, text, button, interactive, order, pollCreation, callLog, revoked,
                    bizContentPlaceholder, v, album, media);
        }

        Mapped with(Album v) {
            return new Mapped(type, errors, text, button, interactive, order, pollCreation, callLog, revoked,
                    bizContentPlaceholder, newsletterFollowerInvite, v, media);
        }

        Mapped with(Button v) {
            return new Mapped(type, errors, text, v, interactive, order, pollCreation, callLog, revoked,
                    bizContentPlaceholder, newsletterFollowerInvite, album, media);
        }

        Mapped with(Interactive v) {
            return new Mapped(type, errors, text, button, v, order, pollCreation, callLog, revoked,
                    bizContentPlaceholder, newsletterFollowerInvite, album, media);
        }

        Mapped with(Order v) {
            return new Mapped(type, errors, text, button, interactive, v, pollCreation, callLog, revoked,
                    bizContentPlaceholder, newsletterFollowerInvite, album, media);
        }
    }

    /** Never null: a type nobody maps becomes {@code unsupported} with Meta's unknown-type error. */
    static Mapped map(JsonNode raw, String id, String rawType) {
        Mapped none = Mapped.NONE;
        if (WaWebBusinessShapes.INTERACTIVE_TYPES.contains(rawType)) {
            WaWebBusinessShapes.Built b = WaWebBusinessShapes.interactive(raw, id);
            return new Mapped("interactive", null, null, null, b.interactive(), null, null, null, null, null, null, null,
                    b.media());
        }
        return switch (rawType) {
            case "poll_creation" -> none.withType("poll_creation").with(poll(raw));
            case "call_log" -> none.withType("call_log").with(call(raw));
            case "revoked" -> none.withType("revoked")
                    .with(new Revoked(WaIds.messageId(raw.path("protocolMessageKey"))));
            case "biz_content_placeholder" -> none.withType("biz_content_placeholder")
                    .with(new BizContentPlaceholder());
            case "newsletter_follower_invite" -> none.withType("newsletter_follower_invite").with(
                    new NewsletterFollowerInvite(text(raw.path("newsletterFollowerInviteInfo").path("newsletterName"))));
            case "album" -> none.withType("album").with(new Album());
            case "buttons_response", "template_button_reply" ->
                    none.withType("button").with(WaWebBusinessShapes.buttonReply(raw));
            case "list_response" -> none.withType("interactive").with(WaWebBusinessShapes.listReply(raw));
            case "rich_response" -> {
                String t = WaWebBusinessShapes.richText(raw.path("unifiedResponse"));
                yield t == null ? unsupported(raw, rawType) : new Mapped("text", null, new Text(t), null, null, null,
                        null, null, null, null, null, null, false);
            }
            case "order" -> {
                Order o = WaWebBusinessShapes.order(raw);
                yield o == null ? unsupported(raw, rawType) : none.withType("order").with(o);
            }
            default -> unsupported(raw, rawType);
        };
    }

    private static Mapped unsupported(JsonNode raw, String rawType) {
        String details = "unknown".equals(rawType) && text(raw.path("subtype")) != null ? text(raw.path("subtype"))
                : rawType;
        return new Mapped("unsupported",
                List.of(new MessageError(UNKNOWN_CODE, UNKNOWN_TITLE, UNKNOWN_TITLE,
                        new MessageError.ErrorData(details), null)),
                null, null, null, null, null, null, null, null, null, null, false);
    }

    private static PollCreation poll(JsonNode raw) {
        List<PollCreation.Option> options = new ArrayList<>();
        // raw {name, localId} objects, or plain strings on a poll the console sent before it used that shape
        raw.path("pollOptions").forEach(o -> options.add(new PollCreation.Option(
                text(o.isObject() ? o.path("name") : o),
                o.path("localId").isNumber() ? o.path("localId").asInt() : null)));
        List<PollCreation.Vote> votes = new ArrayList<>();
        raw.path("pollVotes").forEach(v -> {
            var vote = PollVotes.parse(v);
            if (vote != null) {
                votes.add(new PollCreation.Vote(vote.voter(), vote.fromMe(), vote.options(), vote.at().toEpochMilli()));
            }
        });
        return new PollCreation(text(raw.path("pollName")), options,
                raw.path("pollSelectableOptionsCount").asInt(1) != 1, votes.isEmpty() ? null : votes);
    }

    private static CallLog call(JsonNode raw) {
        String outcome = raw.path("callOutcome").asString("").toLowerCase();
        int seconds = Math.max(raw.path("callDuration").asInt(0), raw.path("duration").asInt(0));
        return new CallLog(raw.path("isVideoCall").asBoolean(false),
                outcome.contains("miss") || outcome.contains("reject"), seconds > 0 ? (long) seconds : null);
    }

    // ---- votes

    /**
     * A {@code vote_update} as a {@code poll_vote} message (a person's vote) or echo (ours), about the poll. Empty
     * without a poll or a voter, and for a poll of a chat that is no person.
     */
    static Optional<WebhookEvent> vote(ChannelContext ctx, JsonNode p, Instant capturedAt) {
        String pollId = text(p.path("pollMessageId"));
        String voter = text(p.path("voter"));
        String chatId = pollId == null ? null : pollChat(pollId);
        if (pollId == null || voter == null || !WaWebWebhookConverter.isPersonChat(chatId)) return Optional.empty();
        List<Integer> options = new ArrayList<>();
        p.path("options").forEach(o -> options.add(o.asInt()));
        long at = p.path("at").asLong(0);
        Instant when = at > 0 ? Instant.ofEpochMilli(at) : capturedAt == null ? Instant.now() : capturedAt;
        String id = "vote:" + pollId + ":" + voter;
        String ts = String.valueOf(when.getEpochSecond());
        PollVote vote = new PollVote(pollId, options);
        WaWeb waWeb = new WaWeb(chatId, null, null, null, null, null, null, null, null, null,
                capturedAt == null ? null : capturedAt.toEpochMilli(), null, null, null);
        if (p.path("fromMe").asBoolean(false)) {
            String to = chatId.endsWith("@c.us") ? WaIds.user(chatId) : null;
            MessageEcho e = new MessageEcho(ctx.displayPhoneNumber(), to, id, ts, "poll_vote", null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, vote, null, null, null, null, null,
                    waWeb);
            return Optional.of(WebhookEvent.of(ctx, List.of(new Change(WaWebWebhookConverter.ECHO_FIELD,
                    Value.of(ctx).withMessageEchoes(List.of(e))))));
        }
        boolean lid = voter.endsWith("@lid");
        Message m = new Message(lid ? null : WaIds.user(voter), lid ? WaIds.user(voter) : null, id, ts, "poll_vote",
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, vote,
                null, null, null, null, null, waWeb);
        return Optional.of(WebhookEvent.of(ctx, List.of(new Change(WaWebWebhookConverter.CHANGE_FIELD,
                Value.of(ctx).withMessages(List.of(m))))));
    }

    /** The chat of a poll message key {@code fromMe_remote_id}. */
    private static String pollChat(String key) {
        int a = key.indexOf('_');
        int b = a < 0 ? -1 : key.indexOf('_', a + 1);
        return b < 0 ? null : key.substring(a + 1, b);
    }

    private static String text(JsonNode n) {
        return n.isTextual() && !n.asString().isEmpty() ? n.asString() : null;
    }
}
