package me.nageshk.omnichannel.inbox;

/**
 * Spring events published by {@link InboxIngestService}. Routing subscribes to {@code ChatCreated} and
 * {@code ChatReopened}; the live streams subscribe to {@code MessageUpserted}, {@code ChatChanged} and
 * {@code ActionRecorded}. The assignment service publishes {@code ChatChanged} and {@code ActionRecorded} too.
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

    /** An entry was added to the chat's audit trail ({@code ROUTED}, {@code ASSIGNED}, {@code REOPENED}, ...). */
    public record ActionRecorded(WaWebChat chat, WaWebChatAction action) {
    }

    /** The chat's summary changed (preview, counts, status). */
    public record ChatChanged(WaWebChat chat) {
    }
}
