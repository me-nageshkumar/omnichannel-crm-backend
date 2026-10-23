package me.nageshk.omnichannel.crm.pipeline.dto;

import java.util.List;

public record StagesRequest(Long version, List<StageRequest> stages) {}
