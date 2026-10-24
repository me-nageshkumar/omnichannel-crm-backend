package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.activity.CrmActivity;
import me.nageshk.omnichannel.crm.deal.CrmDeal;
import me.nageshk.omnichannel.crm.deal.PlacementProbe;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.JsonNode;

class BoardApiIT extends CrmTestBase {

    @Autowired ApplicationContext app;
    @Autowired CrmAccess crmAccess;

    String sales;
    Map<String, String> lanes;
    String aliceId;
    /** The deal the capture of Alice made, first in New. */
    String capturedDeal;

    @BeforeEach
    void setUp() throws Exception {
        enableCrm();
        sales = salesId();
        JsonNode c = captured(contact("Alice Wonder", "919811111111"), number);
        aliceId = c.get("contact").get("id").asText();
        capturedDeal = c.get("deal").get("id").asText();
        lanes = stages(sales);
    }

    private String deal(String title, Object amount) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("pipelineId", sales);
        body.put("primaryCrmContactId", aliceId);
        if (amount != null) body.put("amount", amount);
        return createDeal(body).get("id").asText();
    }

    private JsonNode move(String id, String stage, String before, String after) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stageId", lanes.getOrDefault(stage, stage));
        if (before != null) body.put("beforeDealId", before);
        if (after != null) body.put("afterDealId", after);
        return json(send(post(crm() + "/deals/" + id + "/move"), managerToken, body).andExpect(status().isOk()));
    }

    private JsonNode board(String query) throws Exception {
        return json(send(get(crm() + "/deals/board?" + query), managerToken, null).andExpect(status().isOk()));
    }

    private JsonNode lane(JsonNode board, String stage) {
        for (JsonNode l : board.get("lanes")) if (l.get("stage").get("name").asText().equals(stage)) return l;
        throw new AssertionError("no lane " + stage);
    }

    private List<String> order(String stage) throws Exception {
        List<String> ids = new ArrayList<>();
        lane(board("pipelineId=" + sales + "&perStageLimit=100"), stage).get("deals")
                .forEach(d -> ids.add(d.get("id").asText()));
        return ids;
    }

    private List<Double> positions(String stage) throws Exception {
        List<Double> out = new ArrayList<>();
        lane(board("pipelineId=" + sales + "&perStageLimit=100"), stage).get("deals")
                .forEach(d -> out.add(d.get("position").asDouble()));
        return out;
    }

    @Test
    void boardListsLanesWithTotalsAndSums() throws Exception {
        deal("A", 100);
        deal("B", "50.25");
        move(deal("C", 30), "Won", null, null);
        move(deal("D", 20), "Lost", null, null);
        JsonNode b = board("pipelineId=" + sales);
        assertThat(b.get("pipelineId").asText()).isEqualTo(sales);
        assertThat(b.get("lanes")).hasSize(7);
        List<String> names = new ArrayList<>();
        b.get("lanes").forEach(l -> names.add(l.get("stage").get("name").asText()));
        assertThat(names).containsExactly("New", "Contacted", "Qualified", "Proposal", "Negotiation", "Won", "Lost");
        JsonNode fresh = lane(b, "New");
        assertThat(fresh.get("total").asLong()).isEqualTo(3);
        assertThat(fresh.get("sumAmount").decimalValue()).isEqualByComparingTo("150.25");
        assertThat(fresh.get("deals")).hasSize(3);
        assertThat(fresh.get("deals").get(0).get("id").asText()).isEqualTo(capturedDeal);
        assertThat(fresh.get("deals").get(1).get("title").asText()).isEqualTo("A");
        assertThat(fresh.get("stage").get("id").asText()).isEqualTo(lanes.get("New"));
        assertThat(lane(b, "Won").get("total").asLong()).isEqualTo(1);
        assertThat(lane(b, "Won").get("sumAmount").decimalValue()).isEqualByComparingTo("30");
        assertThat(lane(b, "Lost").get("total").asLong()).isEqualTo(1);
        assertThat(lane(b, "Lost").get("sumAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(lane(b, "Contacted").get("total").asLong()).isZero();
        assertThat(lane(b, "Contacted").get("deals")).isEmpty();
        // the owner and text filters narrow the cards and the totals
        assertThat(lane(board("pipelineId=" + sales + "&q=b"), "New").get("total").asLong()).isEqualTo(1);
        assertThat(lane(board("pipelineId=" + sales + "&ownerId=" + agent.getId()), "New").get("total").asLong())
                .isZero();
        // without a pipeline the default one is shown
        assertThat(board("").get("pipelineId").asText()).isEqualTo(sales);
    }

    @Test
    void moveBetweenNeighboursKeepsOrder() throws Exception {
        String a = deal("A", null);
        String b = deal("B", null);
        String c = deal("C", null);
        assertThat(order("New")).containsExactly(capturedDeal, a, b, c);
        // C between the captured deal and A
        move(c, "New", capturedDeal, a);
        assertThat(order("New")).containsExactly(capturedDeal, c, a, b);
        // B to the top: only the deal that will follow it
        move(b, "New", null, capturedDeal);
        assertThat(order("New")).containsExactly(b, capturedDeal, c, a);
        // the captured deal right after C: only the deal it follows
        move(capturedDeal, "New", c, null);
        assertThat(order("New")).containsExactly(b, c, capturedDeal, a);
        // into an empty lane, then appended after it
        move(a, "Contacted", null, null);
        move(b, "Contacted", a, null);
        assertThat(order("Contacted")).containsExactly(a, b);
        assertThat(order("New")).containsExactly(c, capturedDeal);
        // a reorder within a lane is not a stage change
        assertThat(mongo.count(new Query(Criteria.where("type").is(ActivityType.STAGE_CHANGED)
                .and("target.id").is(c)), CrmActivity.class)).isZero();
        assertThat(mongo.count(new Query(Criteria.where("type").is(ActivityType.STAGE_CHANGED)
                .and("target.id").is(a)), CrmActivity.class)).isEqualTo(1);
        // a neighbour outside the target lane is refused
        send(post(crm() + "/deals/" + c + "/move"), managerToken,
                Map.of("stageId", lanes.get("New"), "beforeDealId", a))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        send(post(crm() + "/deals/" + c + "/move"), managerToken, Map.of("stageId", "nope"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.stageId").exists());
    }

    @Test
    void manyMidpointInsertsRespaceTheLane() throws Exception {
        String last = deal("Last", null);
        List<String> inserted = new ArrayList<>();
        String next = last;
        for (int i = 0; i < 60; i++) {
            String d = deal("D" + i, null);
            // always straight after the captured deal, i.e. between it and the previous insert
            move(d, "New", capturedDeal, next);
            inserted.add(0, d);
            next = d;
        }
        List<String> expected = new ArrayList<>();
        expected.add(capturedDeal);
        expected.addAll(inserted);
        expected.add(last);
        assertThat(order("New")).containsExactlyElementsOf(expected);
        List<Double> p = positions("New");
        assertThat(new HashSet<>(p)).hasSize(p.size());
        for (int i = 1; i < p.size(); i++) assertThat(p.get(i) - p.get(i - 1)).isGreaterThanOrEqualTo(1e-6);
    }

    /** Racy by nature: repeated so a lane re-space that interleaves with another would show. */
    @RepeatedTest(5)
    void concurrentMovesIntoOneGapAllLand() throws Exception {
        String last = deal("Last", null);
        List<String> movers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String d = deal("M" + i, null);
            move(d, "Contacted", null, null);
            movers.add(d);
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> jobs = new ArrayList<>();
            for (String d : movers) {
                jobs.add(() -> send(post(crm() + "/deals/" + d + "/move"), managerToken,
                        Map.of("stageId", lanes.get("New"), "beforeDealId", capturedDeal, "afterDealId", last))
                        .andReturn().getResponse().getStatus());
            }
            for (Future<Integer> f : pool.invokeAll(jobs)) assertThat(f.get()).isEqualTo(200);
        } finally {
            pool.shutdown();
        }
        List<String> o = order("New");
        assertThat(o).hasSize(10);
        assertThat(o.get(0)).isEqualTo(capturedDeal);
        assertThat(o.get(9)).isEqualTo(last);
        assertThat(o.subList(1, 9)).containsExactlyInAnyOrderElementsOf(movers);
        List<Double> p = positions("New");
        Set<Double> distinct = new HashSet<>(p);
        assertThat(distinct).hasSize(p.size());
    }

    @Test
    void moveIntoWonClosesAndOutReopens() throws Exception {
        String d = deal("A", 100);
        JsonNode won = move(d, "Won", null, null);
        assertThat(won.get("status").asText()).isEqualTo("WON");
        assertThat(won.get("stageId").asText()).isEqualTo(lanes.get("Won"));
        assertThat(won.get("closedAt").isNull()).isFalse();
        assertThat(won.get("probability").asInt()).isEqualTo(100);
        assertThat(mongo.count(new Query(Criteria.where("type").is(ActivityType.DEAL_WON).and("target.id").is(d)),
                CrmActivity.class)).isEqualTo(1);
        CrmActivity changed = mongo.findOne(new Query(Criteria.where("type").is(ActivityType.STAGE_CHANGED)
                .and("target.id").is(d)), CrmActivity.class);
        assertThat(changed.getPipelineId()).isEqualTo(sales);
        assertThat(changed.getPayload().get("toStageId")).isEqualTo(lanes.get("Won"));
        JsonNode reopened = move(d, "Contacted", null, null);
        assertThat(reopened.get("status").asText()).isEqualTo("OPEN");
        assertThat(reopened.get("closedAt").isNull()).isTrue();
        assertThat(reopened.get("probability").asInt()).isEqualTo(25);
        assertThat(reopened.get("stageEnteredAt").isNull()).isFalse();
    }

    @Test
    void perStageLimitCapsCardsButNotTotal() throws Exception {
        List<String> all = new ArrayList<>();
        all.add(capturedDeal);
        for (int i = 0; i < 4; i++) all.add(deal("D" + i, 10));
        JsonNode fresh = lane(board("pipelineId=" + sales + "&perStageLimit=2"), "New");
        assertThat(fresh.get("total").asLong()).isEqualTo(5);
        assertThat(fresh.get("sumAmount").decimalValue()).isEqualByComparingTo("40");
        assertThat(fresh.get("deals")).hasSize(2);
        assertThat(fresh.get("deals").get(0).get("id").asText()).isEqualTo(all.get(0));
        assertThat(fresh.get("deals").get(1).get("id").asText()).isEqualTo(all.get(1));
        // load more: the lane through search, by position
        JsonNode more = json(send(post(crm() + "/deals/search"), managerToken, Map.of("pipelineId", sales,
                "stageId", lanes.get("New"), "sort", "position", "page", 1, "size", 2)).andExpect(status().isOk()));
        assertThat(more.get("total").asLong()).isEqualTo(5);
        assertThat(more.get("items").get(0).get("id").asText()).isEqualTo(all.get(2));
        assertThat(more.get("items").get(1).get("id").asText()).isEqualTo(all.get(3));
        // the default limit holds all five; an over-large limit is capped, not refused
        assertThat(lane(board("pipelineId=" + sales), "New").get("deals")).hasSize(5);
        assertThat(lane(board("pipelineId=" + sales + "&perStageLimit=1000"), "New").get("deals")).hasSize(5);
    }

    @Test
    void restrictedBoardOfInaccessiblePipelineIs404() throws Exception {
        String second = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second"))
                .andExpect(status().is2xxSuccessful())).get("id").asText();
        send(get(crm() + "/deals/board?pipelineId=" + sales), agentToken, null).andExpect(status().isNotFound());
        assignPipelines(agent, sales);
        JsonNode b = json(send(get(crm() + "/deals/board?pipelineId=" + sales), agentToken, null)
                .andExpect(status().isOk()));
        assertThat(lane(b, "New").get("total").asLong()).isEqualTo(1);
        send(get(crm() + "/deals/board?pipelineId=" + second), agentToken, null).andExpect(status().isNotFound());
        send(get(crm() + "/deals/board?pipelineId=nope"), managerToken, null).andExpect(status().isNotFound());
        send(get("/api/workspaces/" + other.getId() + "/crm/deals/board?pipelineId=" + sales), managerToken, null)
                .andExpect(status().is4xxClientError());
    }

    @Test
    void placingADealReadBeforeAConcurrentChangeIsAConflict() throws Exception {
        String second = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second"))
                .andExpect(status().is2xxSuccessful())).get("id").asText();
        CrmPipeline salesPipeline = mongo.findById(sales, CrmPipeline.class);
        CrmDeal asRead = mongo.findById(capturedDeal, CrmDeal.class);
        CrmContext ctx = crmAccess.resolve(auth(manager), brand.getId());
        // meanwhile the deal moves to another pipeline
        long v = asRead.getVersion();
        send(put(crm() + "/deals/" + capturedDeal), managerToken, Map.of("version", v, "pipelineId", second))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> PlacementProbe.place(app, ctx, asRead, salesPipeline, lanes.get("Contacted")))
                .isInstanceOfSatisfying(CodedException.class, e -> {
                    assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
                    assertThat(e.status().value()).isEqualTo(409);
                });
        CrmDeal now = mongo.findById(capturedDeal, CrmDeal.class);
        assertThat(now.getPipelineId()).isEqualTo(second);
        assertThat(now.getVersion()).isEqualTo(v + 1);
        // and once it is gone, 404
        send(delete(crm() + "/deals/" + capturedDeal), managerToken, null).andExpect(status().isNoContent());
        assertThatThrownBy(() -> PlacementProbe.place(app, ctx, asRead, salesPipeline, lanes.get("Contacted")))
                .isInstanceOf(NotFoundException.class);
    }
}
