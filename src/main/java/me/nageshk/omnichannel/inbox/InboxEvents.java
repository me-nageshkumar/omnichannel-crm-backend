package me.nageshk.omnichannel.inbox;

/**
 * Spring events published by {@link InboxIngestService}. Routing subscribes to {@code ChatCreated} and
 * {@code ChatReopened}; the live streams subscribe to {@code MessageUpserted} and {@code ChatChanged}.
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

    /** A message was stored ({@code inserted}) or its status moved. */
    public record MessageUpserted(WaWebChat chat, WaWebMessage message, boolean inserted) {
    }

    /** The chat's summary changed (preview, counts, status). */
    public record ChatChanged(WaWebChat chat) {
    }
}
