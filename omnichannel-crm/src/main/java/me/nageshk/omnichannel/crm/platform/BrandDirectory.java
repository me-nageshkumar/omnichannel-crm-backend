package me.nageshk.omnichannel.crm.platform;

import java.util.List;
import java.util.Optional;

/** Brands. */
public interface BrandDirectory {

    Optional<PlatformBrand> find(String workspaceId);

    /** For the demo seeder. */
    Optional<PlatformBrand> findByNames(String customerName, String brandName);

    /** The zone the brand reads dates in (its campaign settings' time zone). */
    java.time.ZoneId zoneOf(String workspaceId);

    /** Every brand id, for the jobs. */
    List<String> allBrandIds();
}
