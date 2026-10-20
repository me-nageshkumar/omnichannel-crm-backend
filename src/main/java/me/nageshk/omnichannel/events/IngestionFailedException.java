package me.nageshk.omnichannel.events;

/**
 * A durable WhatsApp event could not be stored because the database failed. It tells the WebSocket handler not to
 * acknowledge the event, so the extension keeps it in its outbox and replays it after reconnecting; ingestion is
 * idempotent, so the replay is safe.
 */
public class IngestionFailedException extends RuntimeException {

    private final String waMessageId;

    public IngestionFailedException(String eventName, String waMessageId, Throwable cause) {
        super("could not store " + eventName + " " + waMessageId + ": " + cause, cause);
        this.waMessageId = waMessageId;
    }

    /** The WhatsApp id of the message the event is about, for the log. */
    public String waMessageId() {
        return waMessageId;
    }
}
