package me.nageshk.omnichannel.crm.deal;

import me.nageshk.omnichannel.crm.deal.dto.DealView;

/** The deal a capture produced or found, and whether it was made now. */
public record CaptureDeal(DealView deal, boolean created) {}
