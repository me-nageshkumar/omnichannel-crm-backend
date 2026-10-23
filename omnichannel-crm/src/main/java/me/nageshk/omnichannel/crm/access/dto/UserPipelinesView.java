package me.nageshk.omnichannel.crm.access.dto;

import java.util.List;

public record UserPipelinesView(String userId, List<String> pipelineIds) {}
