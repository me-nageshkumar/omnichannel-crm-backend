package me.nageshk.omnichannel.crm.settings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.platform.BrandDirectory;
import me.nageshk.omnichannel.crm.platform.EnablementAudit;
import me.nageshk.omnichannel.crm.platform.PlatformBrand;
import me.nageshk.omnichannel.crm.settings.dto.EnablementRequest;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class CrmSettingsService {

    private final CrmSettingsRepository repository;
    private final BrandDirectory brands;
    private final EnablementAudit audit;

    CrmSettingsService(CrmSettingsRepository repository, BrandDirectory brands, EnablementAudit audit) {
        this.repository = repository;
        this.brands = brands;
        this.audit = audit;
    }

    public Optional<CrmSettings> find(String workspaceId) {
        return repository.findByWorkspaceId(workspaceId);
    }

    /** The brand's settings, created with the reference defaults on first use; safe against concurrent creation. */
    public CrmSettings getOrCreate(String workspaceId, String customerId) {
        return repository.findByWorkspaceId(workspaceId).orElseGet(() -> {
            try {
                return repository.insert(CrmDefaults.newSettings(workspaceId, customerId));
            } catch (DuplicateKeyException e) {
                return repository.findByWorkspaceId(workspaceId).orElseThrow(() -> e);
            }
        });
    }

    /** The brand, which must belong to the customer. */
    public PlatformBrand requireBrand(String customerId, String workspaceId) {
        return brands.find(workspaceId).filter(b -> b.customerId().equals(customerId))
                .orElseThrow(() -> new NotFoundException("Workspace not found"));
    }

    public CrmSettings setSwitch(AuthenticatedUser by, String customerId, String workspaceId, EnablementRequest req) {
        requireBrand(customerId, workspaceId);
        Map<String, String> errors = new LinkedHashMap<>();
        if (req.enabled() == null) errors.put("enabled", "is required");
        if (req.maxPipelines() != null && req.maxPipelines() < 1) errors.put("maxPipelines", "must be at least 1");
        if (req.maxCustomFields() != null && req.maxCustomFields() < 1)
            errors.put("maxCustomFields", "must be at least 1");
        if (!errors.isEmpty()) throw new ValidationException(errors);
        CrmSettings s = getOrCreate(workspaceId, customerId);
        boolean before = s.isEnabled();
        s.setEnabled(req.enabled());
        s.setMaxPipelines(req.maxPipelines());
        s.setMaxCustomFields(req.maxCustomFields());
        s.setLastModifiedBy(by.userId());
        s = repository.save(s);
        if (before != s.isEnabled()) audit.recordCrmSwitch(by, workspaceId, before, s.isEnabled());
        return s;
    }
}
