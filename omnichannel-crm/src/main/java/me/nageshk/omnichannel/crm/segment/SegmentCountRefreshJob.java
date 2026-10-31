package me.nageshk.omnichannel.crm.segment;

import me.nageshk.omnichannel.crm.platform.BrandDirectory;
import me.nageshk.omnichannel.crm.settings.CrmSettings;
import me.nageshk.omnichannel.crm.settings.CrmSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Hourly (spec D C9): recounts every segment of every brand whose CRM is on, over the brand-wide universe. A brand
 * or segment that fails is logged and skipped; the job is idempotent, so a second instance running it does no harm.
 */
@Component
public class SegmentCountRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(SegmentCountRefreshJob.class);

    private final BrandDirectory brands;
    private final CrmSettingsService settings;
    private final SegmentService segments;

    public SegmentCountRefreshJob(BrandDirectory brands, CrmSettingsService settings, SegmentService segments) {
        this.brands = brands;
        this.settings = settings;
        this.segments = segments;
    }

    public void run() {
        for (String workspaceId : brands.allBrandIds()) {
            if (!settings.find(workspaceId).filter(CrmSettings::isEnabled).isPresent()) continue;
            try {
                segments.refreshCounts(workspaceId);
            } catch (RuntimeException e) {
                log.warn("segment counts of brand {} not refreshed: {}", workspaceId, e.toString());
            }
        }
    }
}
