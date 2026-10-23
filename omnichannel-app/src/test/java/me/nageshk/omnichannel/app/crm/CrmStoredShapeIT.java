package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.access.PipelineAccess;
import me.nageshk.omnichannel.crm.activity.ActivityLog;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.TargetType;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Query;

/** How CRM documents look in Mongo: embedded ids stay {@code id}, and the declared indexes exist. */
class CrmStoredShapeIT extends CrmTestBase {

    @Autowired ActivityLog log;

    @Test
    void embeddedIdsAreStoredAsId() throws Exception {
        enableCrm();
        send(get(crm() + "/pipelines"), managerToken, null);
        CrmContext ctx = new CrmContext(brand.getId(), acme.getId(), manager.getId(), java.util.Set.of(), null,
                List.of(), java.util.Set.of(), PipelineAccess.ALL);
        log.record(ctx, ActivityType.SYSTEM, new CrmTarget(TargetType.CONTACT, "c1"),
                List.of(new CrmTarget(TargetType.DEAL, "d1")), null, null, "x", new HashMap<>());

        Document a = mongo.findOne(new Query(), Document.class, "crmActivities");
        Document target = a.get("target", Document.class);
        assertThat(target.containsKey("id")).isTrue();
        assertThat(target.containsKey("_id")).isFalse();
        Document related = a.getList("relatedTargets", Document.class).get(0);
        assertThat(related.containsKey("id")).isTrue();
        assertThat(related.containsKey("_id")).isFalse();

        Document p = mongo.findOne(new Query(), Document.class, "crmPipelines");
        Document stage = p.getList("stages", Document.class).get(0);
        assertThat(stage.containsKey("id")).isTrue();
        assertThat(stage.containsKey("_id")).isFalse();
    }

    @Test
    void crmContactsHasTheDeclaredIndexes() {
        var infos = mongo.indexOps("crmContacts").getIndexInfo();
        assertThat(infos).extracting(i -> i.getName()).contains("ws_contact_channel", "ws_pipelines");
        var wild = infos.stream().filter(i -> i.getName().equals("ws_customFields")).findFirst().orElseThrow();
        assertThat(wild.getIndexFields().get(0).getKey()).isEqualTo("workspaceId");
        assertThat(wild.getIndexFields().get(1).getKey()).isEqualTo("customFields.$**");
        assertThat(infos).allSatisfy(i -> assertThat(i.getName().equals("_id_")
                || i.getIndexFields().get(0).getKey().equals("workspaceId")).isTrue());
    }

    @Test
    void crmCompaniesHasTheDeclaredIndexes() {
        var infos = mongo.indexOps("crmCompanies").getIndexInfo();
        assertThat(infos).extracting(i -> i.getName()).contains("ws_id", "ws_name", "ws_owner");
        assertThat(infos).allSatisfy(i -> assertThat(i.getName().equals("_id_")
                || i.getIndexFields().get(0).getKey().equals("workspaceId")).isTrue());
    }
}
