package me.nageshk.omnichannel.inbox;

/**
 * Spring events of the inbox. {@link InboxIngestService} publishes all six; routing publishes {@code ActionRecorded};
 * assignment publishes {@code ChatChanged} and {@code ActionRecorded}; a console or campaign send publishes
 * {@code MessageUpserted} then {@code ChatChanged}; the media cache publishes {@code MessageUpserted} alone; marking a
 * chat read publishes {@code ChatChanged}. Routing subscribes to {@code ChatCreated}, {@code ChatReopened} and
 * {@code CampaignChatFirstReply}; the live streams subscribe to {@code MessageUpserted} (the message only),
 * {@code ChatChanged} (the chat row, once per change) and {@code ActionRecorded}.
 */
public final class InboxEvents {

    private InboxEvents() {
    }

    /** A chat row was just created by its first message, which travelled {@code firstDirection}. */
    public record ChatCreated(WaWebChat chat, MessageDirection firstDirection) {
    }

    /** An inbound message moved a closed chat back to OPEN. */
    public record ChatReopened(WaWebChat chat) {
    }

    /**
     * The contact answered a campaign's chat for the first time since our last message: an open chat with neither a
     * user nor a team, which routing now gives to the number's team or agent.
     */
    public record CampaignChatFirstReply(WaWebChat chat) {
    }

    /** A message was stored ({@code inserted}) or its status moved. */
    public record MessageUpserted(WaWebChat chat, WaWebMessage message, boolean inserted) {
    }

    /** An entry was added to the chat's audit trail ({@code ROUTED}, {@code ASSIGNED}, {@code REOPENED}, ...). */
    public record ActionRecorded(WaWebChat chat, WaWebChatAction action) {
    }

    /** The chat's summary changed (preview, counts, status). */
    public record ChatChanged(WaWebChat chat) {
    }
}
