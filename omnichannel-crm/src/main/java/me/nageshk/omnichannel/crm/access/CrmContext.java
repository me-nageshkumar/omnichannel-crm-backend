package me.nageshk.omnichannel.crm.access;

import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.common.api.ForbiddenException;
import me.nageshk.omnichannel.crm.platform.PlatformNumber;
import me.nageshk.omnichannel.crm.settings.CrmSettings;

/** Everything one request needs to know about the caller in a brand, built once by {@link CrmAccess}. */
public record CrmContext(String workspaceId, String customerId, String userId, Set<String> authorities,
        CrmSettings settings, List<PlatformNumber> numbers, Set<String> chattable, PipelineAccess pipelines) {

    public boolean can(String code) {
        return authorities.contains(code);
    }

    /** @throws ForbiddenException (403) without the authority */
    public void require(String code) {
        if (!can(code)) throw new ForbiddenException("requires " + code);
    }

    public boolean viewAll() {
        return can("crm:view-all");
    }
}
