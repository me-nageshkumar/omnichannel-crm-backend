package me.nageshk.omnichannel.service.page;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.db.page.PagesAddressStatus;

/** What the link address screens read (spec 2026-11-18 link addresses §5.8). */
public final class PagesAddressViews {

    private PagesAddressViews() {
    }

    /** {@code inUse}: links of the brand use this address now (it is working). */
    public record AddressView(String baseUrl, PagesAddressStatus status, Instant checkedAt, String lastError,
            boolean inUse) {
    }

    public record FormerHostView(String host, Instant until) {
    }

    public record BrandRow(String workspaceId, String workspaceName, AddressView address,
            List<FormerHostView> formerHosts, Long version) {
    }

    /** {@code keepDays}: how long an earlier address stays known (the pages' lifetime), for the screen's warning. */
    public record CustomerView(String platformBaseUrl, List<BrandRow> brands, int keepDays) {
    }
}
