package me.nageshk.omnichannel.contact.advanced;

import java.time.ZoneId;

/** The zone a workspace reads dates in; implemented by the campaign settings so contact imports nothing from campaign. */
public interface WorkspaceZone {

    ZoneId zoneOf(String workspaceId);
}
