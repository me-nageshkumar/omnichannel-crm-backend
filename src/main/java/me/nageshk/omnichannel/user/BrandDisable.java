package me.nageshk.omnichannel.user;

import me.nageshk.omnichannel.enablement.Disabled;

/** A person's access to one brand switched off; kept when their role there is removed or changed. */
public record BrandDisable(String workspaceId, Disabled disabled) {}
