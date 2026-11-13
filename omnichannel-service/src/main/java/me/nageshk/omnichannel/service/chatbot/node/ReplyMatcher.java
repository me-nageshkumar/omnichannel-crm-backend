package me.nageshk.omnichannel.service.chatbot.node;

import java.util.Map;
import java.util.Optional;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.campaign.CampaignMessageListener;
import me.nageshk.omnichannel.service.cloud.CloudMessageValidator;

/**
 * Which button or row a reply picked (spec §4.2): the interactive reply id when the stored webhook event carries one
 * (a Cloud channel, or a business button over the bridge), else the title the customer sent as text.
 */
public final class ReplyMatcher {

    private ReplyMatcher() {}

    public static Optional<Interactive.Reply> match(Interactive i, WaWebMessage reply, Map<String, Object> webhookMessage) {
        if (i == null || reply == null) return Optional.empty();
        var replies = CloudMessageValidator.replies(i);
        String id = replyId(webhookMessage);
        if (id != null) {
            for (Interactive.Reply r : replies) if (id.equals(r.id())) return Optional.of(r);
        }
        if (reply.getBody() == null) return Optional.empty();
        String bare = CampaignMessageListener.bare(reply.getBody());
        if (bare == null || bare.isEmpty()) return Optional.empty();
        for (Interactive.Reply r : replies) {
            if (r.title() != null && bare.equals(CampaignMessageListener.bare(r.title()))) return Optional.of(r);
        }
        return Optional.empty();
    }

    /** {@code button.payload}, {@code interactive.button_reply.id} or {@code interactive.list_reply.id}, else null. */
    static String replyId(Map<String, Object> m) {
        if (m == null) return null;
        Object button = m.get("button");
        if (button instanceof Map<?, ?> b && b.get("payload") instanceof String s && !s.isBlank()) return s;
        Object interactive = m.get("interactive");
        if (interactive instanceof Map<?, ?> in) {
            for (String key : new String[] {"button_reply", "list_reply"}) {
                if (in.get(key) instanceof Map<?, ?> r && r.get("id") instanceof String s && !s.isBlank()) return s;
            }
        }
        return null;
    }
}
