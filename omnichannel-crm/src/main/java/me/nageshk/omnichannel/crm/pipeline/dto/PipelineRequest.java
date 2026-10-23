package me.nageshk.omnichannel.crm.pipeline.dto;

import java.util.List;

/**
 * Create: {@code name}, optional {@code stages} (default: the Sales lanes), {@code active}, {@code rottingDays},
 * {@code isDefault}. Update: the same scalar fields plus {@code version}; {@code stages} are ignored (use the lanes endpoint).
 */
public record PipelineRequest(String name, Boolean active, Integer rottingDays, Boolean isDefault,
        List<StageRequest> stages, Long version) {}
