package me.nageshk.omnichannel.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.springframework.http.HttpMethod.POST;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.contact.DoNotContact;
import me.nageshk.omnichannel.inbox.WaWebChat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class CampaignStartIT extends CampaignTestBase {

    @Autowired CampaignRecipientRepository recipients;

    private Map<RecipientStatus, Long> byStatus(String id) {
        return recipients.findAll().stream().filter(r -> r.getCampaignId().equals(id))
                .collect(Collectors.groupingBy(CampaignRecipient::getStatus, Collectors.counting()));
    }

    private CampaignRecipient only(String id, String phone) {
        return recipients.findAll().stream().filter(r -> id.equals(r.getCampaignId()) && phone.equals(r.getPhone()))
                .findFirst().orElseThrow();
    }

    @Test
    void filterIsMaterialisedInIdOrderAcrossPages() {
        List<Contact> batch = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            Contact c = new Contact();
            c.setWorkspaceId(brand.getId());
            c.setCustomerId(acme.getId());
            c.setName("Lead " + i);
            c.setPhone(String.format("9198%08d", i));
            batch.add(c);
        }
        Contact hidden = new Contact();
        hidden.setWorkspaceId(brand.getId());
        hidden.setCustomerId(acme.getId());
        hidden.setName("Hidden");
        batch.add(hidden);
        List<Contact> saved = mongo.insertAll(batch).stream().filter(c -> c.getPhone() != null).toList();
        String filterId = filter("Everyone", Map.of());
        String t = templateNamed("Start Hi", "Hi {{contact.name}}");
        String id = api(POST, "/campaigns", manager,
                filterCampaign("Big", filterId, List.of(t), List.of(), List.of(n1.getId()))).getBody().get("id").asString();

        ResponseEntity<JsonNode> res = api(POST, "/campaigns/" + id + "/start", manager, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("status").asString()).isEqualTo("RUNNING");
        List<CampaignRecipient> rs = recipients.findAll(Sort.by("_id"));
        assertThat(rs).extracting(CampaignRecipient::getContactId)
                .containsExactlyElementsOf(saved.stream().map(Contact::getId).toList());
        assertThat(rs).allMatch(r -> r.getStatus() == RecipientStatus.PENDING);
        assertThat(campaign(id).getCounts().total()).isEqualTo(1200);
        assertThat(campaign(id).getCounts().pending()).isEqualTo(1200);
        assertThat(campaign(id).getStatus()).isEqualTo(CampaignStatus.RUNNING);
        assertThat(campaign(id).getStartedAt()).isEqualTo(clock.instant());
    }

    @Test
    void templatesAreAssignedRoundRobin() {
        for (int i = 0; i < 5; i++) contact("Lead " + i, "91981111000" + i);
        String t1 = template("One", "Hi {{contact.name}}");
        String t2 = template("Two", "Hello {{contact.name}}");
        String id = api(POST, "/campaigns", manager, filterCampaign("RR", filter("All", Map.of()), List.of(t1, t2),
                List.of(), List.of(n1.getId()))).getBody().get("id").asString();
        api(POST, "/campaigns/" + id + "/start", manager, null);

        assertThat(recipients.findAll(Sort.by("_id"))).extracting(CampaignRecipient::getTemplateId)
                .containsExactly(t1, t2, t1, t2, t1);
    }

    @Test
    void csvOf20000RowsWithDuplicatesBlanksAndBadPhones() {
        StringBuilder csv = new StringBuilder("phone,name\n");
        for (int i = 0; i < 19880; i++) csv.append(String.format("98%08d", i)).append(",N").append(i).append('\n');
        for (int i = 0; i < 100; i++) csv.append(String.format("98%08d", i)).append(",Dup\n");
        for (int i = 0; i < 10; i++) csv.append(",Blank\n");
        for (int i = 0; i < 10; i++) csv.append("12345,Bad\n");
        String csvText = csv.toString();
        String[] id = new String[1];

        assertTimeout(Duration.ofSeconds(20), () -> id[0] = startedCsvCampaign("Huge", csvText, "name", n1));

        Campaign.Counts counts = campaign(id[0]).getCounts();
        assertThat(counts.total()).isEqualTo(20000);
        assertThat(counts.pending()).isEqualTo(19880);
        assertThat(counts.skipped()).isEqualTo(120);
        Map<SkipReason, Long> reasons = recipients.findAll().stream().filter(r -> r.getSkipReason() != null)
                .collect(Collectors.groupingBy(CampaignRecipient::getSkipReason, Collectors.counting()));
        assertThat(reasons).containsEntry(SkipReason.DUPLICATE, 100L).containsEntry(SkipReason.NO_PHONE, 10L)
                .containsEntry(SkipReason.INVALID_PHONE, 10L);
        assertThat(recipients.findAll()).filteredOn(r -> r.getStatus() == RecipientStatus.PENDING)
                .allMatch(r -> r.getPhone().startsWith("91"));
        assertThat(gridFsFiles()).isZero();
        assertThat(campaign(id[0]).getAudience().csvFileId()).isNull();
        assertThat(campaign(id[0]).getStatus()).isEqualTo(CampaignStatus.RUNNING);
    }

    @Test
    void doNotContactAndRecentlyMessagedAreSkipped() {
        Contact dnc = contact("Dnc", "919811110001");
        mongo.updateFirst(new Query(Criteria.where("_id").is(dnc.getId())),
                new Update().set("doNotContact", new DoNotContact(clock.instant(), DoNotContact.Reason.MANUAL, null)),
                Contact.class);
        contact("Recent", "919811110002");
        contact("Older", "919811110003");
        CampaignRecipient recent = recipient("elsewhere", "919811110002");
        recent.setStatus(RecipientStatus.SENT);
        recent.setSentAt(clock.instant().minus(Duration.ofDays(6)));
        mongo.save(recent);
        CampaignRecipient older = recipient("elsewhere", "919811110003");
        older.setStatus(RecipientStatus.DELIVERED);
        older.setSentAt(clock.instant().minus(Duration.ofDays(8)));
        mongo.save(older);

        String id = startedFilterCampaign("Skips", List.of("919811110001", "919811110002", "919811110003"), n1);

        assertThat(only(id, "919811110001").getSkipReason()).isEqualTo(SkipReason.DO_NOT_CONTACT);
        assertThat(only(id, "919811110002").getSkipReason()).isEqualTo(SkipReason.RECENTLY_MESSAGED);
        assertThat(only(id, "919811110003").getStatus()).isEqualTo(RecipientStatus.PENDING);
        assertThat(campaign(id).getCounts().skipped()).isEqualTo(2);
    }

    private void chatOn(Contact c, String phoneNumberId, java.time.Instant at) {
        WaWebChat chat = new WaWebChat();
        chat.setWorkspaceId(brand.getId());
        chat.setCustomerId(acme.getId());
        chat.setPhoneNumberId(phoneNumberId);
        chat.setRemoteId(c.getPhone() + "@c.us");
        chat.setContactId(c.getId());
        chat.setLastMessageAt(at);
        mongo.save(chat);
    }

    @Test
    void existingChatPinsTheSender() {
        Contact chatted = contact("Chatted", "919811110001");
        contact("Fresh", "919811110002");
        chatOn(chatted, n2.getId(), clock.instant());

        String both = startedFilterCampaign("Both", List.of("919811110001", "919811110002"), n1, n2);
        assertThat(only(both, "919811110001").getPreferredSenderId()).isEqualTo(n2.getId());
        assertThat(only(both, "919811110001").getStatus()).isEqualTo(RecipientStatus.PENDING);
        assertThat(only(both, "919811110002").getPreferredSenderId()).isNull();

        String oneOnly = startedFilterCampaign("OnlyN1", List.of("919811110001", "919811110002"), n1);
        assertThat(only(oneOnly, "919811110001").getSkipReason()).isEqualTo(SkipReason.CHAT_ON_OTHER_NUMBER);
        assertThat(only(oneOnly, "919811110002").getStatus()).isEqualTo(RecipientStatus.PENDING);
    }

    @Test
    void startTwiceIs409AndStartRevalidates() {
        contact("Lead", "919811110001");
        String t = template("Plain", "Hi {{contact.name}}");
        String first = api(POST, "/campaigns", manager, filterCampaign("First", filter("All", Map.of()), List.of(t),
                List.of(), List.of(n1.getId()))).getBody().get("id").asString();
        assertThat(api(POST, "/campaigns/" + first + "/start", manager, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> again = api(POST, "/campaigns/" + first + "/start", manager, null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("CAMPAIGN_STATE");
        assertThat(again.getBody().get("message").asString()).isEqualTo("This campaign cannot be started now.");

        String t2 = template("Linked", "Hi {{contact.name}}");
        String second = api(POST, "/campaigns", manager, filterCampaign("Second", filter("All 2", Map.of()),
                List.of(t2), List.of(), List.of(n1.getId()))).getBody().get("id").asString();
        mongo.updateFirst(new Query(Criteria.where("_id").is(t2)),
                new Update().set("content.body", "Visit http://x.example {{contact.name}}"),
                me.nageshk.omnichannel.template.MessageTemplate.class);
        ResponseEntity<JsonNode> res = api(POST, "/campaigns/" + second + "/start", manager, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asString()).isEqualTo("TEMPLATE_HAS_LINK");
        assertThat(campaign(second).getStatus()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(recipients.findAll()).noneMatch(r -> second.equals(r.getCampaignId()));
    }

    @Test
    void startNeedsCampaignManage() {
        contact("Lead", "919811110001");
        String t = template("Plain", "Hi {{contact.name}}");
        String id = api(POST, "/campaigns", manager, filterCampaign("Guarded", filter("All", Map.of()), List.of(t),
                List.of(), List.of(n1.getId()))).getBody().get("id").asString();
        assertThat(api(POST, "/campaigns/" + id + "/start", agent, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(campaign(id).getStatus()).isEqualTo(CampaignStatus.DRAFT);
    }
}
