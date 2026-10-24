package me.nageshk.omnichannel.crm.pipeline.dto;

import me.nageshk.omnichannel.crm.pipeline.StageType;

/**
 * A lane in the requested order. {@code id} is null for a new lane. An entry naming an existing lane with a
 * {@code moveToStageId} is a removal: that lane is dropped and its deals go to the (kept) target lane.
 */
public record StageRequest(String id, String name, String color, Integer probability, StageType type, Integer wipLimit,
        String moveToStageId) {}
