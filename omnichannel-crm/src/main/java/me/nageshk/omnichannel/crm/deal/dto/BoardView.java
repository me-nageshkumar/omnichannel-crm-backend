package me.nageshk.omnichannel.crm.deal.dto;

import java.math.BigDecimal;
import java.util.List;
import me.nageshk.omnichannel.crm.pipeline.CrmStage;

/** A pipeline's lanes in stage order; {@code sumAmount} adds the OPEN and WON amounts of the whole lane. */
public record BoardView(String pipelineId, List<Lane> lanes) {

    public record Lane(CrmStage stage, long total, BigDecimal sumAmount, List<DealView> deals) {}
}
