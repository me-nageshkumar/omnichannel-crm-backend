package me.nageshk.omnichannel.inbox;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * One-off for design §3.4: {@code inboxSettings} used to hold one document per phone number. Those documents move
 * to {@code inboxSettingsLegacy} (kept so a team override can be seeded from them when the team becomes a number's
 * owner, see {@link #legacyFor}) and each brand gets a default seeded from the most common routing among its
 * numbers. A brand that already has a default keeps it. Also owns the collection's unique index (see
 * {@link InboxSetting}). Once no per-number document is left it only checks the index.
 */
@Component
@Order(5)
public class InboxSettingsMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InboxSettingsMigration.class);
    static final String COLLECTION = "inboxSettings";
    static final String LEGACY = "inboxSettingsLegacy";
    static final String INDEX = "workspace_team_unique";
    static final String OLD_INDEX = "phone_unique";

    private final MongoTemplate mongo;
    private final InboxSettingRepository settings;

    public InboxSettingsMigration(MongoTemplate mongo, InboxSettingRepository settings) {
        this.mongo = mongo;
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    public void migrate() {
        List<Document> legacy = mongo.find(new Query(where("phoneNumberId").exists(true)), Document.class, COLLECTION);
        if (legacy.isEmpty()) {
            ensureIndex();
            return;
        }
        Map<String, List<Document>> byWorkspace = new LinkedHashMap<>();
        for (Document d : legacy) {
            mongo.save(d, LEGACY);
            mongo.remove(new Query(where("_id").is(d.get("_id"))), COLLECTION);
            byWorkspace.computeIfAbsent(d.getString("workspaceId"), k -> new ArrayList<>()).add(d);
        }
        // the per-number unique index would treat every default as phoneNumberId null: replace it before seeding
        ensureIndex();
        byWorkspace.forEach((workspaceId, docs) -> {
            if (settings.findByWorkspaceIdAndTeamId(workspaceId, null).isPresent()) return;
            Document first = docs.get(0);
            InboxSetting s = new InboxSetting();
            s.setCustomerId(first.getString("customerId"));
            s.setWorkspaceId(workspaceId);
            s.setRouting(new InboxSetting.Routing(mostCommonMode(docs), null));
            s.setClaimOnReply(first.getBoolean("claimOnReply", true));
            s.setKeepAssignmentOnClose(first.getBoolean("keepAssignmentOnClose", false));
            settings.save(s);
        });
        log.info("moved {} per-number inbox setting(s) to {} and seeded {} brand default(s)", legacy.size(), LEGACY,
                byWorkspace.size());
    }

    /** Drops the per-number unique index (it would reject two defaults) and creates the per-scope one. */
    void ensureIndex() {
        IndexOperations ops = mongo.indexOps(COLLECTION);
        boolean hasOld = ops.getIndexInfo().stream().anyMatch(i -> OLD_INDEX.equals(i.getName()));
        if (hasOld) ops.dropIndex(OLD_INDEX);
        ops.createIndex(new Index().named(INDEX).on("workspaceId", Sort.Direction.ASC).on("teamId", Sort.Direction.ASC)
                .unique());
    }

    /** The per-number document a number had before the migration, if any. */
    public Optional<Document> legacyFor(String phoneNumberId) {
        return Optional.ofNullable(mongo.findOne(new Query(where("phoneNumberId").is(phoneNumberId)), Document.class,
                LEGACY));
    }

    /** USER counts as TEAM (a member is a team-level choice); a tie is settled as TEAM, the spec default. */
    private static RoutingMode mostCommonMode(List<Document> docs) {
        Map<RoutingMode, Integer> counts = new EnumMap<>(RoutingMode.class);
        for (Document d : docs) {
            Document routing = d.get("routing", Document.class);
            RoutingMode mode = RoutingMode.NONE;
            if (routing != null && routing.getString("mode") != null) {
                try {
                    mode = RoutingMode.valueOf(routing.getString("mode"));
                } catch (IllegalArgumentException unknown) {
                    mode = RoutingMode.NONE;
                }
            }
            if (mode == RoutingMode.USER) mode = RoutingMode.TEAM;
            counts.merge(mode, 1, Integer::sum);
        }
        int best = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<RoutingMode> leaders = counts.entrySet().stream().filter(e -> e.getValue() == best).map(Map.Entry::getKey)
                .toList();
        return leaders.size() == 1 ? leaders.get(0) : RoutingMode.TEAM;
    }
}
