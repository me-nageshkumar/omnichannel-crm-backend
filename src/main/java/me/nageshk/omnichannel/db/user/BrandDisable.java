package me.nageshk.omnichannel.db.user;

import me.nageshk.omnichannel.db.enablement.Disabled;

/** A person's access to one brand switched off; kept when their role there is removed or changed. */
public record BrandDisable(String workspaceId, Disabled disabled) {}
