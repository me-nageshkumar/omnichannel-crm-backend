package me.nageshk.omnichannel.crm.pipeline.dto;

import java.util.List;

/**
 * Create: {@code name}, optional {@code stages} (default: the Sales lanes), {@code active}, {@code rottingDays},
 * {@code isDefault}. Update: {@code name} and the required {@code version}; absent (or null) {@code active},
 * {@code isDefault} and {@code rottingDays} keep the stored value, {@code rottingDays: 0} clears it (the brand's
 * days apply); {@code stages} are ignored (use the lanes endpoint).
 */
public record PipelineRequest(String name, Boolean active, Integer rottingDays, Boolean isDefault,
        List<StageRequest> stages, Long version) {}
