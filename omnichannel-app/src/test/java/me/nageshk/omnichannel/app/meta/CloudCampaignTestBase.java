package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.CampaignSender;
import me.nageshk.omnichannel.db.campaign.CampaignSettings;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactFilter;
import me.nageshk.omnichannel.service.campaign.CampaignRunner;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Cloud campaigns (spec 2026-11-21): a brand with a Meta connection and one Cloud number "333" in WABA "222"
 * (TIER_250, quality GREEN), at Tuesday 11:00 IST, inside the default sending window.
 */
abstract class CloudCampaignTestBase extends MetaTestBase {

    static final String FOLLOW_UP = CloudTemplatesIT.FOLLOW_UP;
    static final String BROCHURE = CloudTemplatesIT.BROCHURE;
    static final String PENDING = CloudTemplatesIT.PENDING;

    @Autowired protected CampaignRunner runner;
    @Autowired protected me.nageshk.omnichannel.service.campaign.CloudCampaignSenders cloudSenders;

    protected String connectionId;
    protected String numberId;

    @Override
    protected List<Class<?>> collections() {
        List<Class<?>> c = new ArrayList<>(super.collections());
        c.add(Campaign.class);
        c.add(CampaignRecipient.class);
        c.add(CampaignSender.class);
        c.add(CampaignSettings.class);
        c.add(ContactFilter.class);
        return c;
    }

    @BeforeEach
    void cloudNumber() {
        // holds live in memory on the shared bean; a test's hold must not reach the next test
        for (String f : new String[] {"wabaHolds", "numberHolds"}) {
            ((Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(cloudSenders, f)).clear();
        }
        connectionId = connect("111").get("id").asString();
        numberId = addCloudNumber(connectionId, "Admissions", "333", "+91 98765 43210").get("id").asString();
        GRAPH.reset();
    }

    /** {@code path} is relative to the brand's {@code /api/workspaces/{id}}. */
    protected ResponseEntity<JsonNode> ws(HttpMethod m, String path, me.nageshk.omnichannel.db.user.User who,
            Object body) {
        return rest.exchange(url("/api/workspaces/" + brand.getId() + path), m, auth(who, body), JsonNode.class);
    }

    /** Imports {@code json} as a template of WABA 222; returns the stored id. */
    protected String syncTemplate(String json) {
        GRAPH.enqueue("GET /222/message_templates", 200, "{\"data\":[" + json + "]}");
        ResponseEntity<JsonNode> res = meta(HttpMethod.POST, "/templates/sync", manager, Map.of());
        assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.OK);
        String name = mapper.readTree(json).get("name").asString();
        for (JsonNode t : meta(HttpMethod.GET, "/templates", manager, null).getBody()) {
            if (name.equals(t.get("name").asString())) return t.get("id").asString();
        }
        throw new AssertionError("template " + name + " not imported");
    }

    protected Contact contact(String name, String phone) {
        Contact c = mongo.findOne(new Query(Criteria.where("workspaceId").is(brand.getId()).and("phone").is(phone)),
                Contact.class);
        if (c != null) return c;
        c = new Contact();
        c.setWorkspaceId(brand.getId());
        c.setCustomerId(acme.getId());
        c.setName(name);
        c.setPhone(phone);
        return mongo.save(c);
    }

    /** The request body of a Cloud campaign over all the brand's contacts. */
    protected Map<String, Object> cloudRequest(String name, String filterId, String templateId, String headerMediaId,
            List<Map<String, Object>> placeholders, List<String> senderIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("channel", "WHATSAPP_CLOUD_API");
        body.put("audience", Map.of("kind", "FILTER", "filterId", filterId));
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("cloudTemplateId", templateId);
        t.put("headerMediaId", headerMediaId);
        body.put("cloudTemplate", t);
        body.put("templateIds", List.of());
        body.put("placeholders", placeholders);
        body.put("senderIds", senderIds);
        return body;
    }

    protected String allContactsFilter(String name) {
        ResponseEntity<JsonNode> res = ws(HttpMethod.POST, "/contact-filters", manager,
                Map.of("name", name, "filter", Map.of()));
        assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
        return res.getBody().get("id").asString();
    }

    /** Creates the contacts and a Cloud campaign draft over them; returns its id. */
    protected String cloudCampaign(String name, String templateId, List<String> phones,
            List<Map<String, Object>> placeholders, String... senderIds) {
        for (int i = 0; i < phones.size(); i++) contact("Lead " + i, phones.get(i));
        ResponseEntity<JsonNode> res = ws(HttpMethod.POST, "/campaigns", manager, cloudRequest(name,
                allContactsFilter("All " + name), templateId, null, placeholders, List.of(senderIds)));
        assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
        return res.getBody().get("id").asString();
    }

    /** The mapping of positional blank {@code body:1} to the contact's name, "there" when blank. */
    protected static List<Map<String, Object>> nameIntoBody1() {
        return List.of(Map.of("name", "body:1", "source", Map.of("kind", "CONTACT_FIELD", "field", "name"),
                "fallback", "there"), Map.of("name", "body:2", "source", Map.of("kind", "FIXED", "text", "BCA"), "fallback", ""));
    }

    protected void start(String id) {
        ResponseEntity<JsonNode> res = ws(HttpMethod.POST, "/campaigns/" + id + "/start", manager, null);
        assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.OK);
    }

    protected void tickNow() throws Exception {
        runner.tick().get(30, TimeUnit.SECONDS);
    }

    protected List<CampaignRecipient> recipients(String campaignId) {
        return mongo.find(new Query(Criteria.where("campaignId").is(campaignId)).with(Sort.by("_id")),
                CampaignRecipient.class);
    }

    protected static String sent(String wamid) {
        return "{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"" + wamid + "\"}]}";
    }

    /** A small PNG uploaded as the app's media of the Cloud number; returns its id. */
    protected String uploadPng() {
        byte[] png = new byte[100];
        System.arraycopy(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, 0, png, 0, 8);
        org.springframework.http.HttpHeaders fileHeaders = new org.springframework.http.HttpHeaders();
        fileHeaders.setContentType(org.springframework.http.MediaType.IMAGE_PNG);
        fileHeaders.setContentDispositionFormData("file", "brochure.png");
        org.springframework.util.MultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("file", new org.springframework.http.HttpEntity<>(new org.springframework.core.io.ByteArrayResource(png) {
            @Override
            public String getFilename() {
                return "brochure.png";
            }
        }, fileHeaders));
        form.add("messaging_product", "whatsapp");
        form.add("type", "image");
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.putAll(auth(manager, null).getHeaders());
        h.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<JsonNode> res = rest.exchange(url("/api/workspaces/" + brand.getId() + "/channel-accounts/"
                + numberId + "/media"), HttpMethod.POST, new org.springframework.http.HttpEntity<>(form, h),
                JsonNode.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).as(String.valueOf(res.getBody())).isTrue();
        return res.getBody().get("id").asString();
    }
}
