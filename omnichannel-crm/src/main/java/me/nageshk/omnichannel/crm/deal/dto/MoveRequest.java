package me.nageshk.omnichannel.crm.deal.dto;

/**
 * Into (or within) {@code stageId}: {@code beforeDealId} is the card that ends up directly above the moved one,
 * {@code afterDealId} the card directly below it. Neither appends to the lane.
 */
public record MoveRequest(String stageId, String beforeDealId, String afterDealId) {}
