package me.nageshk.omnichannel.crm.deal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.deal.dto.BoardView;
import me.nageshk.omnichannel.crm.deal.dto.DealView;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import me.nageshk.omnichannel.crm.pipeline.CrmPipelineService;
import me.nageshk.omnichannel.crm.pipeline.CrmStage;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** A pipeline as lanes of cards (spec 6.2). More cards of a lane come from the deal search sorted by position. */
@Service
public class BoardService {

    static final int DEFAULT_PER_STAGE = 25;
    static final int MAX_PER_STAGE = 100;

    private final MongoTemplate mongo;
    private final CrmPipelineService pipelines;
    private final DealService deals;

    BoardService(MongoTemplate mongo, CrmPipelineService pipelines, DealService deals) {
        this.mongo = mongo;
        this.pipelines = pipelines;
        this.deals = deals;
    }

    /**
     * @param pipelineId null = the brand's default pipeline
     * @param perStageLimit cards per lane (default {@value #DEFAULT_PER_STAGE}, capped to 1..{@value #MAX_PER_STAGE})
     * @throws NotFoundException (404) for a missing or inaccessible pipeline
     */
    public BoardView board(CrmContext ctx, String pipelineId, Integer perStageLimit, String ownerId, String q) {
        String id = DealService.present(pipelineId) ? pipelineId.trim() : ctx.settings().getDefaultPipelineId();
        if (id == null) throw new NotFoundException("Pipeline not found");
        CrmPipeline pipeline = pipelines.get(ctx, id);
        int limit = perStageLimit == null ? DEFAULT_PER_STAGE : Math.max(1, Math.min(perStageLimit, MAX_PER_STAGE));

        List<Criteria> all = new ArrayList<>();
        all.add(Criteria.where("workspaceId").is(ctx.workspaceId()));
        all.add(Criteria.where("pipelineId").is(pipeline.getId()));
        if (DealService.present(ownerId)) all.add(Criteria.where("ownerId").is(ownerId.trim()));
        Criteria text = DealService.textCriteria(q);
        if (text != null) all.add(text);
        Criteria filter = new Criteria().andOperator(all);

        Map<String, Long> totals = new HashMap<>();
        Map<String, BigDecimal> sums = new HashMap<>();
        Document sumOpenAndWon = new Document("$sum", new Document("$cond", List.of(
                new Document("$in", List.of("$status", List.of(DealStatus.OPEN.name(), DealStatus.WON.name()))),
                new Document("$ifNull", List.of("$amount", 0)), 0)));
        for (Document g : mongo.getCollection(Placement.DEALS).aggregate(List.of(
                new Document("$match", filter.getCriteriaObject()),
                new Document("$group", new Document("_id", "$stageId").append("total", new Document("$sum", 1))
                        .append("sumAmount", sumOpenAndWon))))) {
            String stageId = String.valueOf(g.get("_id"));
            totals.put(stageId, ((Number) g.get("total")).longValue());
            sums.put(stageId, decimal(g.get("sumAmount")));
        }

        List<CrmStage> stages = Placement.ordered(pipeline);
        Map<String, List<CrmDeal>> cards = new HashMap<>();
        List<CrmDeal> shown = new ArrayList<>();
        for (CrmStage s : stages) {
            if (totals.getOrDefault(s.getId(), 0L) == 0) continue;
            List<CrmDeal> lane = mongo.find(new Query(new Criteria().andOperator(filter,
                    Criteria.where("stageId").is(s.getId())))
                    .with(Sort.by(Sort.Order.asc("position"), Sort.Order.asc("_id"))).limit(limit), CrmDeal.class);
            cards.put(s.getId(), lane);
            shown.addAll(lane);
        }
        Map<String, DealView> views = new HashMap<>();
        deals.views(ctx, shown).forEach(v -> views.put(v.id(), v));
        List<BoardView.Lane> lanes = new ArrayList<>();
        for (CrmStage s : stages) {
            List<DealView> laneViews = cards.getOrDefault(s.getId(), List.of()).stream()
                    .map(d -> views.get(d.getId())).toList();
            lanes.add(new BoardView.Lane(s, totals.getOrDefault(s.getId(), 0L),
                    sums.getOrDefault(s.getId(), BigDecimal.ZERO), laneViews));
        }
        return new BoardView(pipeline.getId(), lanes);
    }

    private static BigDecimal decimal(Object v) {
        if (v instanceof Decimal128 d) return d.bigDecimalValue();
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return BigDecimal.ZERO;
    }
}
