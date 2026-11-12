package me.nageshk.omnichannel.service.page;

import java.time.Instant;

/**
 * Told what a customer did on a message page (spec §5.7); implemented in the application module, where the CRM
 * lives. Called once per page open and once per distinct button, row, link or location; never fails a beacon.
 */
public interface MessagePageObserver {

    /** One thing done: the host contact, the channel account (the sending number) and the message it belongs to. */
    record PageActivity(String workspaceId, String contactId, String channelAccountId, String messageId, String kind,
            String id, String title, Double latitude, Double longitude, Instant at) {}

    void opened(PageActivity e);

    void acted(PageActivity e);
}
