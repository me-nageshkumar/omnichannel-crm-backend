package me.nageshk.omnichannel.meta.api;

import java.util.List;

/** Where verified Meta changes go: the host stores them, then processes the new ones after Meta has its answer. */
public interface CloudWebhookSink {

    /**
     * {@code failed} when any change could not be stored (Meta must deliver again); {@code afterReply} processes the
     * changes stored for the first time, and runs even when another change failed.
     */
    record Delivery(boolean failed, Runnable afterReply) {}

    Delivery accept(List<CloudChange> changes);
}
