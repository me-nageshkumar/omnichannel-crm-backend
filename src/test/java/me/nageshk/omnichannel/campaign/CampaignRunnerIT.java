package me.nageshk.omnichannel.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.contact.ContactService;
import me.nageshk.omnichannel.contact.DoNotContact;
import me.nageshk.omnichannel.enablement.Disabled;
import me.nageshk.omnichannel.enablement.EnableLevel;
import me.nageshk.omnichannel.inbox.WaWebChat;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.rpc.Envelope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;

/** The runner's tick against a device that answers on its own; Wednesday 11:00 IST, gaps at their minima. */
class CampaignRunnerIT extends CampaignTestBase {

    static final String P1 = "919811100001";
    static final String P2 = "919811100002";
    static final String P3 = "919811100003";

    @Autowired ContactService contacts;
    @Autowired PhoneNumberRepository numbers;
    @Autowired CampaignSettingsService settingsService;
    @Autowired ApplicationContext context;

    /** {@code n} phones 919811100001, 919811100002, … */
    static List<String> phones(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(String.format("9198111%05d", i));
        return out;
    }

    long usedToday(PhoneNumber n) {
        return senders.usedToday(n.getId(), settingsService.get(brand.getId()), clock.instant());
    }

    /** Creates and starts a CSV campaign on n1 whose phone column is {@code Phone} and name column {@code Name}. */
    String csvCampaign(String name, String csv, String templateId, List<Map<String, Object>> placeholders) {
        return csvCampaign(name, csv, templateId, placeholders, n1);
    }

    String csvCampaign(String name, String csv, String templateId, List<Map<String, Object>> placeholders,
            PhoneNumber sender) {
        Map<String, Object> audience = new LinkedHashMap<>();
        audience.put("kind", "CSV");
        audience.put("phoneColumn", "Phone");
        audience.put("nameColumn", "Name");
        Map<String, Object> body = Map.of("name", name, "audience", audience, "templateIds", List.of(templateId),
                "placeholders", placeholders, "senderIds", List.of(sender.getId()));
        String id = multipart(HttpMethod.POST, "/campaigns", manager, body, csv).getBody().get("id").asString();
        api(HttpMethod.POST, "/campaigns/" + id + "/start", manager, null);
        return id;
    }

    /** Holds both threads of {@code campaignScheduler} until the returned latch is counted down. */
    CountDownLatch holdCampaignScheduler() {
        CountDownLatch gate = new CountDownLatch(1);
        ThreadPoolTaskScheduler scheduler = context.getBean("campaignScheduler", ThreadPoolTaskScheduler.class);
        for (int i = 0; i < 2; i++) {
            scheduler.execute(() -> {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        return gate;
    }

    /** {@code sendMessage} requests to the phone across the devices. */
    static long sendsTo(String phone, AutoDevice... devices) {
        return Arrays.stream(devices).flatMap(d -> d.requests("sendMessage").stream())
                .filter(e -> e.params().get("chatId").asString().equals(phone + "@c.us")).count();
    }

    static Map<String, Object> column(String placeholder, String column) {
        return Map.of("name", placeholder, "source", Map.of("kind", "CSV_COLUMN", "column", column), "fallback", "");
    }

    @Test
    void looksUpThenSendsOneMessagePerTick() throws Exception {
        try (AutoDevice d = device(n1)) {
            String id = startedFilterCampaign("Diwali", List.of("919811100001", "919811100002"), n1);
            tickNow();
            assertThat(d.requests()).extracting(Envelope::method).containsExactly("getNumberId", "sendMessage");
            assertThat(d.requests("sendMessage").get(0).params().get("content").asString()).isEqualTo("Hi Lead 0");
            CampaignRecipient r = recipient(id, "919811100001");
            assertThat(r.getStatus()).isEqualTo(RecipientStatus.SENT);
            assertThat(r.getSentAt()).isEqualTo(WED_11_IST);
            assertThat(sender(n1).getNextSendAt()).isEqualTo(WED_11_IST.plusSeconds(45));
            assertThat(sender(n1).getSendingDays()).isEqualTo(1);
            tickNow();                                   // gap not elapsed
            assertThat(d.requests()).hasSize(2);
            clock.advance(Duration.ofSeconds(45)); tickNow();
            assertThat(d.requests("sendMessage")).hasSize(2);
            assertThat(campaign(id).getCounts().sent()).isEqualTo(2);
        }
    }

    @Test
    void warmupCapOfFiveOnDayOne() throws Exception {
        try (AutoDevice d = device(n1)) {
            String id = startedFilterCampaign("Six", phones(6), n1);
            tickNow();
            for (int i = 0; i < 8; i++) {
                clock.advance(Duration.ofSeconds(45));
                tickNow();
            }
            assertThat(d.requests("sendMessage")).hasSize(5);
            assertThat(campaign(id).getCounts().pending()).isEqualTo(1);
            assertThat(recipient(id, phones(6).get(5)).getStatus()).isEqualTo(RecipientStatus.PENDING);

            clock.set(Instant.parse("2026-10-22T04:30:00Z"));   // Thursday 10:00 IST
            tickNow();
            assertThat(d.requests("sendMessage")).hasSize(6);
            assertThat(recipient(id, phones(6).get(5)).getStatus()).isEqualTo(RecipientStatus.SENT);
            assertThat(sender(n1).getSendingDays()).isEqualTo(2);
        }
    }

    @Test
    void hourlyCapCountsSendsAndSkips() throws Exception {
        try (AutoDevice d = device(n1)) {
            List<String> phones = phones(14);
            Set<String> unregistered = Set.of(phones.get(2), phones.get(5), phones.get(8));
            d.lookupAnswer = p -> unregistered.contains(p) ? null : AutoDevice.registered(p);
            String id = startedFilterCampaign("Hourly", phones, n1);
            sender(n1, s -> s.setSendingDays(20));
            Instant first = clock.instant();
            for (Instant t = first; !t.isAfter(first.plus(Duration.ofMinutes(59))); t = t.plusSeconds(45)) {
                clock.set(t);
                tickNow();
            }
            assertThat(d.requests("getNumberId")).hasSize(12);
            assertThat(d.requests("sendMessage")).hasSize(9);
            assertThat(campaign(id).getCounts().skipped()).isEqualTo(3);

            clock.set(first.plus(Duration.ofMinutes(60)));
            tickNow();
            assertThat(d.requests("getNumberId")).hasSize(13);
            assertThat(d.requests("sendMessage")).hasSize(10);
        }
    }

    @Test
    void halfPaceDoublesTheGap() throws Exception {
        try (AutoDevice d = device(n1)) {
            startedFilterCampaign("Half", List.of(P1), n1);
            sender(n1, s -> s.setHalfPaceUntil(Instant.parse("2026-10-21T18:30:00Z")));
            tickNow();
            assertThat(d.requests("sendMessage")).hasSize(1);
            assertThat(sender(n1).getNextSendAt()).isEqualTo(WED_11_IST.plusSeconds(90));
        }
    }

    @Test
    void outsideTheWindowNothingHappens() throws Exception {
        try (AutoDevice d = device(n1)) {
            String id = startedFilterCampaign("Window", List.of(P1), n1);
            clock.set(Instant.parse("2026-10-21T04:29:00Z"));   // 09:59 IST
            tickNow();
            clock.set(Instant.parse("2026-10-21T12:30:00Z"));   // 18:00 IST
            tickNow();
            clock.set(Instant.parse("2026-10-25T05:30:00Z"));   // Sunday 11:00 IST
            tickNow();
            mongo.updateFirst(new Query(Criteria.where("workspaceId").is(brand.getId())),
                    new Update().set("timezone", "UTC"), CampaignSettings.class);
            clock.set(Instant.parse("2026-10-21T05:30:00Z"));   // Wednesday 05:30 UTC
            tickNow();
            assertThat(d.requests()).isEmpty();
            assertThat(recipient(id, P1).getStatus()).isEqualTo(RecipientStatus.PENDING);
        }
    }

    @Test
    void dayStartOffsetDelaysTheFirstSend() throws Exception {
        try (AutoDevice d = device(n1)) {
            random.offset = 45;
            clock.set(Instant.parse("2026-10-21T05:00:00Z"));   // 10:30 IST
            startedFilterCampaign("Offset", List.of(P1), n1);
            tickNow();
            assertThat(d.requests()).isEmpty();
            clock.set(Instant.parse("2026-10-21T05:15:00Z"));   // 10:45 IST
            tickNow();
            assertThat(d.requests("sendMessage")).hasSize(1);
        }
    }

    @Test
    void unregisteredNumberIsSkippedWithAShortGap() throws Exception {
        try (AutoDevice d = device(n1)) {
            d.lookupAnswer = p -> null;
            String id = startedFilterCampaign("Unregistered", List.of(P1, P2), n1);
            tickNow();
            CampaignRecipient r = recipient(id, P1);
            assertThat(r.getStatus()).isEqualTo(RecipientStatus.SKIPPED);
            assertThat(r.getSkipReason()).isEqualTo(SkipReason.NOT_ON_WHATSAPP);
            assertThat(r.getAttemptedAt()).isEqualTo(WED_11_IST);
            assertThat(d.requests()).extracting(Envelope::method).containsExactly("getNumberId");
            assertThat(sender(n1).getNextSendAt()).isEqualTo(WED_11_IST.plusSeconds(15));
            assertThat(senders.hourlyUsed(n1.getId(), clock.instant())).isEqualTo(1);
            assertThat(usedToday(n1)).isZero();
            assertThat(sender(n1).getSendingDays()).isZero();
        }
    }

    @Test
    void cachedUnregisteredIsAFreeSkip() throws Exception {
        try (AutoDevice d = device(n1)) {
            seedLookup(P1, false);
            String id = startedFilterCampaign("Cached", List.of(P1, P2), n1);
            tickNow();
            CampaignRecipient skipped = recipient(id, P1);
            assertThat(skipped.getStatus()).isEqualTo(RecipientStatus.SKIPPED);
            assertThat(skipped.getSkipReason()).isEqualTo(SkipReason.NOT_ON_WHATSAPP);
            assertThat(skipped.getAttemptedAt()).isNull();
            assertThat(recipient(id, P2).getStatus()).isEqualTo(RecipientStatus.SENT);
            assertThat(d.requests()).extracting(Envelope::method).containsExactly("getNumberId", "sendMessage");
            assertThat(d.requests("getNumberId").get(0).params().get("phone").asString()).isEqualTo(P2);
        }
    }

    @Test
    void lookupFailuresPauseTheSender() throws Exception {
        try (AutoDevice d = device(n1)) {
            d.lookupAnswer = p -> {
                throw new IllegalStateException("no answer");
            };
            String id = startedFilterCampaign("Retry", List.of(P1), n1);
            tickNow();
            CampaignRecipient r = recipient(id, P1);
            assertThat(r.getStatus()).isEqualTo(RecipientStatus.PENDING);
            assertThat(r.getAttempts()).isZero();
            assertThat(r.getError()).isNull();
            assertThat(r.getAttemptedAt()).isNull();
            assertThat(r.getSenderId()).isNull();
            assertThat(sender(n1).getLookupFailures()).isEqualTo(1);
            assertThat(sender(n1).getNextSendAt()).isEqualTo(WED_11_IST.plusSeconds(45));
            assertThat(usedToday(n1)).isZero();
            clock.advance(Duration.ofSeconds(45));
            tickNow();
            assertThat(sender(n1).getLookupFailures()).isEqualTo(2);
            assertThat(sender(n1).getPausedUntil()).isNull();
            clock.advance(Duration.ofSeconds(45));
            tickNow();
            r = recipient(id, P1);
            assertThat(r.getStatus()).isEqualTo(RecipientStatus.PENDING);
            assertThat(r.getAttempts()).isZero();
            CampaignSender s = sender(n1);
            assertThat(s.getPausedUntil()).isEqualTo(Instant.parse("2026-10-21T18:30:00Z"));
            assertThat(s.getPauseReason()).isEqualTo("LOOKUP_FAILED");
            assertThat(s.getLookupFailures()).isZero();
            JsonNode view = null;
            for (JsonNode v : api(HttpMethod.GET, "/campaigns/senders", manager, null).getBody())
                if (v.get("phoneNumberId").asString().equals(n1.getId())) view = v;
            assertThat(view.get("pauseReason").asString()).isEqualTo("LOOKUP_FAILED");
            clock.advance(Duration.ofSeconds(45));
            tickNow();
            assertThat(d.requests("getNumberId")).hasSize(3);
            assertThat(d.requests("sendMessage")).isEmpty();
            assertThat(campaign(id).getStatus()).isEqualTo(CampaignStatus.RUNNING);
        }
    }

    @Test
    void aLookupThatWorksClearsTheFailures() throws Exception {
        try (AutoDevice d = device(n1)) {
            startedFilterCampaign("Clear", List.of(P1), n1);
            sender(n1, s -> s.setLookupFailures(2));
            tickNow();
            assertThat(d.requests("sendMessage")).hasSize(1);
            assertThat(sender(n1).getLookupFailures()).isZero();
        }
    }

    @Test
    void refusedSendPausesTheCampaignAndReleasesTheClaim() throws Exception {
        try (AutoDevice d = device(n1)) {
            String id = startedFilterCampaign("Refused", List.of(P1), n1);
            String templateId = campaign(id).getTemplateIds().get(0);
            api(HttpMethod.POST, "/templates/" + templateId + "/archive", manager, null);
            tickNow();
            CampaignRecipient r = recipient(id, P1);
            assertThat(r.getStatus()).isEqualTo(RecipientStatus.PENDING);
            assertThat(r.getSenderId()).isNull();
            assertThat(r.getAttemptedAt()).isNull();
            assertThat(r.getDispatchedAt()).isNull();
            assertThat(r.getError()).isNull();
            Campaign c = campaign(id);
            assertThat(c.getStatus()).isEqualTo(CampaignStatus.PAUSED);
            assertThat(c.getPausedBy()).isEqualTo(PausedBy.SYSTEM);
            assertThat(c.getPauseReason()).isEqualTo("SEND_REFUSED");
            assertThat(d.requests("sendMessage")).isEmpty();
            assertThat(usedToday(n1)).isZero();
            assertThat(sender(n1).getNextSendAt()).isNull();
        }
    }

    @Test
    void sendFailureFailsTheRecipientAndCostsTheSlot() throws Exception {
        try (AutoDevice d = device(n1)) {
            d.sendAnswer = req -> Envelope.errorResponse(req.id(), "SEND_FAILED", "could not send");
            String id = startedFilterCampaign("Fails", List.of(P1), n1);
            tickNow();
            CampaignRecipient r = recipient(id, P1);
            assertThat(r.getStatus()).isEqualTo(RecipientStatus.FAILED);
            assertThat(r.getError()).isEqualTo("SEND_FAILED");
            assertThat(usedToday(n1)).isEqualTo(1);
            assertThat(campaign(id).getCounts().failed()).isEqualTo(1);
            assertThat(sender(n1).getNextSendAt()).isEqualTo(WED_11_IST.plusSeconds(45));
            assertThat(sender(n1).getSendingDays()).isEqualTo(1);
        }
    }

    @Test
    void deviceGoneBeforeSendReleasesTheClaim() throws Exception {
        try (AutoDevice d = device(n1)) {
            d.onLookup(() -> {
                PhoneNumber n = numbers.findById(n1.getId()).orElseThrow();
                n.setDisabled(new Disabled("manager", EnableLevel.BRAND, WED_11_IST));
                numbers.save(n);
            });
            String id = startedFilterCampaign("Gone", List.of(P1), n1);
            tickNow();
            CampaignRecipient r = recipient(id, P1);
            assertThat(r.getStatus()).isEqualTo(RecipientStatus.PENDING);
            assertThat(r.getSenderId()).isNull();
            assertThat(r.getAttemptedAt()).isNull();
            assertThat(r.getAttempts()).isZero();
            assertThat(d.requests()).extracting(Envelope::method).containsExactly("getNumberId");
            assertThat(usedToday(n1)).isZero();
            assertThat(sender(n1).getNextSendAt()).isNull();
            assertThat(sender(n1).getSendingDays()).isZero();
        }
    }

    @Test
    void alreadyDeliveredResultMarksTheRecipientDelivered() throws Exception {
        try (AutoDevice d = device(n1)) {
            d.sendAnswer = req -> Envelope.response(req.id(), AutoDevice.model(req, 2));
            String id = startedFilterCampaign("Delivered", List.of(P1), n1);
            tickNow();
            CampaignRecipient r = recipient(id, P1);
            assertThat(r.getStatus()).isEqualTo(RecipientStatus.DELIVERED);
            assertThat(r.getDeliveredAt()).isNotNull();
            assertThat(r.getSentAt()).isEqualTo(WED_11_IST);
            assertThat(r.getMessageId()).isNotNull();
            assertThat(campaign(id).getCounts().delivered()).isEqualTo(1);
        }
    }

    @Test
    void samePhoneInTwoRunningCampaignsIsMessagedOnce() throws Exception {
        try (AutoDevice d = device(n1)) {
            String a = startedFilterCampaign("A", List.of(P1), n1);
            String b = startedFilterCampaign("B", List.of(P1, P2), n1);
            assertThat(recipient(b, P1).getStatus()).isEqualTo(RecipientStatus.PENDING);
            tickNow();
            assertThat(recipient(a, P1).getStatus()).isEqualTo(RecipientStatus.SENT);
            assertThat(d.requests("sendMessage")).hasSize(1);

            clock.advance(Duration.ofSeconds(45));
            tickNow();
            CampaignRecipient skipped = recipient(b, P1);
            assertThat(skipped.getStatus()).isEqualTo(RecipientStatus.SKIPPED);
            assertThat(skipped.getSkipReason()).isEqualTo(SkipReason.RECENTLY_MESSAGED);
            assertThat(skipped.getAttemptedAt()).isNull();
            assertThat(recipient(b, P2).getStatus()).isEqualTo(RecipientStatus.SENT);
            assertThat(d.requests("sendMessage")).extracting(e -> e.params().get("chatId").asString())
                    .containsExactly(P1 + "@c.us", P2 + "@c.us");
        }
    }

    /** A device that never answers a send to {@code phone} (the call times out) and answers every other one. */
    static void neverAnswerSendsTo(AutoDevice d, String phone) {
        d.sendAnswer = req -> req.params().get("chatId").asString().equals(phone + "@c.us") ? null
                : Envelope.response(req.id(), AutoDevice.model(req, 1));
    }

    @Test
    void timedOutSendCountsAsRecentAtMaterialisation() throws Exception {
        try (AutoDevice d = device(n1)) {
            neverAnswerSendsTo(d, P1);
            String a = startedFilterCampaign("A", List.of(P1), n1);
            tickNow();
            CampaignRecipient failed = recipient(a, P1);
            assertThat(failed.getStatus()).isEqualTo(RecipientStatus.FAILED);
            assertThat(failed.getError()).isEqualTo("TIMEOUT");
            assertThat(failed.getDispatchedAt()).isEqualTo(WED_11_IST);
            assertThat(api(HttpMethod.GET, "/campaigns/" + a + "/recipients", manager, null).getBody()
                    .get("items").get(0).get("dispatchedAt").asString()).isEqualTo("2026-10-21T05:30:00Z");

            String b = startedFilterCampaign("B", List.of(P1), n1);
            CampaignRecipient skipped = recipient(b, P1);
            assertThat(skipped.getStatus()).isEqualTo(RecipientStatus.SKIPPED);
            assertThat(skipped.getSkipReason()).isEqualTo(SkipReason.RECENTLY_MESSAGED);
        }
    }

    @Test
    void timedOutSendCountsAsRecentAtClaim() throws Exception {
        try (AutoDevice d = device(n1)) {
            neverAnswerSendsTo(d, P1);
            String a = startedFilterCampaign("A", List.of(P1), n1);
            String b = startedFilterCampaign("B", List.of(P1, P2), n1);
            assertThat(recipient(b, P1).getStatus()).isEqualTo(RecipientStatus.PENDING);
            tickNow();
            assertThat(recipient(a, P1).getError()).isEqualTo("TIMEOUT");

            clock.advance(Duration.ofSeconds(45));
            tickNow();
            CampaignRecipient skipped = recipient(b, P1);
            assertThat(skipped.getStatus()).isEqualTo(RecipientStatus.SKIPPED);
            assertThat(skipped.getSkipReason()).isEqualTo(SkipReason.RECENTLY_MESSAGED);
            assertThat(recipient(b, P2).getStatus()).isEqualTo(RecipientStatus.SENT);
            assertThat(sendsTo(P1, d)).isEqualTo(1);
        }
    }

    @Test
    void interruptedSendCountsAsRecent() throws Exception {
        String a = startedFilterCampaign("A", List.of(P1), n1);
        Instant now = clock.instant();
        mongo.updateFirst(new Query(Criteria.where("campaignId").is(a).and("phone").is(P1)),
                new Update().set("status", RecipientStatus.SENDING).set("senderId", n1.getId())
                        .set("attemptedAt", now.minus(Duration.ofMinutes(3)))
                        .set("dispatchedAt", now.minus(Duration.ofMinutes(3))), CampaignRecipient.class);
        tickNow();
        assertThat(recipient(a, P1).getError()).isEqualTo("INTERRUPTED");
        String b = startedFilterCampaign("B", List.of(P1), n1);
        assertThat(recipient(b, P1).getSkipReason()).isEqualTo(SkipReason.RECENTLY_MESSAGED);
    }

    @Test
    void doNotContactMarkedAfterStartIsSkipped() throws Exception {
        try (AutoDevice d = device(n1)) {
            String id = startedFilterCampaign("Dnc", List.of(P1), n1);
            Contact c = contactWithPhone("Lead 0", P1);
            contacts.markDoNotContact(brand.getId(), c.getId(), DoNotContact.Reason.MANUAL, null);
            tickNow();
            CampaignRecipient r = recipient(id, P1);
            assertThat(r.getStatus()).isEqualTo(RecipientStatus.SKIPPED);
            assertThat(r.getSkipReason()).isEqualTo(SkipReason.DO_NOT_CONTACT);
            assertThat(r.getAttemptedAt()).isNull();
            assertThat(d.requests()).isEmpty();
            assertThat(sender(n1).getNextSendAt()).isNull();
        }
    }

    @Test
    void preferredSenderIsWaitedFor() throws Exception {
        try (AutoDevice d = device(n1)) {
            String id = startedFilterCampaign("Preferred", List.of(P1, P2, P3), n1, n2);
            mongo.updateFirst(new Query(Criteria.where("campaignId").is(id).and("phone").is(P2)),
                    new Update().set("preferredSenderId", n2.getId()), CampaignRecipient.class);
            for (int i = 0; i < 3; i++) {
                tickNow();
                clock.advance(Duration.ofSeconds(45));
            }
            assertThat(d.requests("sendMessage")).extracting(e -> e.params().get("chatId").asString())
                    .containsExactly(P1 + "@c.us", P3 + "@c.us");
            CampaignRecipient waiting = recipient(id, P2);
            assertThat(waiting.getStatus()).isEqualTo(RecipientStatus.PENDING);
            assertThat(waiting.getSenderId()).isNull();
            assertThat(campaign(id).getStatus()).isEqualTo(CampaignStatus.RUNNING);
        }
    }

    @Test
    void recipientWaitingForARetiredSenderIsSkippedAndTheCampaignCompletes() throws Exception {
        String id = startedFilterCampaign("Retired preferred", List.of(P1), n1, n2);
        mongo.updateFirst(new Query(Criteria.where("campaignId").is(id).and("phone").is(P1)),
                new Update().set("preferredSenderId", n2.getId()), CampaignRecipient.class);
        sender(n2, s -> s.setState(SenderState.RETIRED));
        tickNow();
        CampaignRecipient r = recipient(id, P1);
        assertThat(r.getStatus()).isEqualTo(RecipientStatus.SKIPPED);
        assertThat(r.getSkipReason()).isEqualTo(SkipReason.CHAT_ON_OTHER_NUMBER);
        assertThat(campaign(id).getCounts().skipped()).isEqualTo(1);
        assertThat(campaign(id).getStatus()).isEqualTo(CampaignStatus.COMPLETED);
    }

    @Test
    void recipientWaitingForADisabledNumberIsSkipped() throws Exception {
        String id = startedFilterCampaign("Disabled preferred", List.of(P1, P2), n1, n2);
        mongo.updateFirst(new Query(Criteria.where("campaignId").is(id).and("phone").is(P1)),
                new Update().set("preferredSenderId", n2.getId()), CampaignRecipient.class);
        PhoneNumber n = numbers.findById(n2.getId()).orElseThrow();
        n.setDisabled(new Disabled("manager", EnableLevel.BRAND, WED_11_IST));
        numbers.save(n);
        tickNow();
        assertThat(recipient(id, P1).getSkipReason()).isEqualTo(SkipReason.CHAT_ON_OTHER_NUMBER);
        assertThat(recipient(id, P2).getStatus()).isEqualTo(RecipientStatus.PENDING);
        assertThat(campaign(id).getStatus()).isEqualTo(CampaignStatus.RUNNING);
    }

    @Test
    void recipientWaitingForARestingSenderKeepsWaiting() throws Exception {
        String id = startedFilterCampaign("Resting preferred", List.of(P1), n1, n2);
        mongo.updateFirst(new Query(Criteria.where("campaignId").is(id).and("phone").is(P1)),
                new Update().set("preferredSenderId", n2.getId()), CampaignRecipient.class);
        sender(n2, s -> {
            s.setState(SenderState.RESTING);
            s.setRestUntil(WED_11_IST.plus(Duration.ofDays(7)));
        });
        tickNow();
        assertThat(recipient(id, P1).getStatus()).isEqualTo(RecipientStatus.PENDING);
        assertThat(campaign(id).getStatus()).isEqualTo(CampaignStatus.RUNNING);
    }

    @Test
    void notReadySenderIsSkippedWithoutStateChange() throws Exception {
        String id = startedFilterCampaign("NotReady", List.of(P1), n1);
        tickNow();
        CampaignRecipient r = recipient(id, P1);
        assertThat(r.getStatus()).isEqualTo(RecipientStatus.PENDING);
        assertThat(r.getSenderId()).isNull();
        assertThat(r.getAttemptedAt()).isNull();
        CampaignSender s = sender(n1);
        assertThat(s.getState()).isEqualTo(SenderState.WARMING);
        assertThat(s.getNextSendAt()).isNull();
        assertThat(s.getSendingDays()).isZero();
    }

    @Test
    void stuckSendingIsInterruptedNotRetried() throws Exception {
        String id = startedFilterCampaign("Stuck", List.of(P1, P2), n1);
        Instant now = clock.instant();
        mongo.updateFirst(new Query(Criteria.where("campaignId").is(id).and("phone").is(P1)),
                new Update().set("status", RecipientStatus.SENDING).set("senderId", n1.getId())
                        .set("attemptedAt", now.minus(Duration.ofMinutes(2)).minusSeconds(3)),
                CampaignRecipient.class);
        mongo.updateFirst(new Query(Criteria.where("campaignId").is(id).and("phone").is(P2)),
                new Update().set("status", RecipientStatus.SENDING).set("senderId", n1.getId())
                        .set("attemptedAt", now.minus(Duration.ofMinutes(1))), CampaignRecipient.class);
        assertThat(runner.failStuck(now)).isEqualTo(1);
        CampaignRecipient stuck = recipient(id, P1);
        assertThat(stuck.getStatus()).isEqualTo(RecipientStatus.FAILED);
        assertThat(stuck.getError()).isEqualTo("INTERRUPTED");
        assertThat(stuck.getAttemptedAt()).isEqualTo(now.minus(Duration.ofMinutes(2)).minusSeconds(3));
        assertThat(recipient(id, P2).getStatus()).isEqualTo(RecipientStatus.SENDING);
        assertThat(campaign(id).getCounts().failed()).isEqualTo(1);
        try (AutoDevice d = device(n1)) {
            tickNow();
            assertThat(d.requests()).isEmpty();
            assertThat(recipient(id, P1).getStatus()).isEqualTo(RecipientStatus.FAILED);
        }
    }

    @Test
    void stuckSendingOfAStoppedCampaignIsInterruptedToo() throws Exception {
        String id = startedFilterCampaign("Stopped", List.of(P1), n1);
        mongo.updateFirst(new Query(Criteria.where("_id").is(id)),
                new Update().set("status", CampaignStatus.TERMINATED), Campaign.class);
        mongo.updateFirst(new Query(Criteria.where("campaignId").is(id).and("phone").is(P1)),
                new Update().set("status", RecipientStatus.SENDING).set("senderId", n1.getId())
                        .set("attemptedAt", clock.instant().minus(Duration.ofMinutes(3))), CampaignRecipient.class);
        tickNow();
        CampaignRecipient r = recipient(id, P1);
        assertThat(r.getStatus()).isEqualTo(RecipientStatus.FAILED);
        assertThat(r.getError()).isEqualTo("INTERRUPTED");
    }

    @Test
    void campaignCompletesWhenNothingIsLeft() throws Exception {
        try (AutoDevice d = device(n1)) {
            String id = startedFilterCampaign("Done", List.of(P1), n1);
            String paused = startedFilterCampaign("Paused", List.of(P1, P2), n1);
            mongo.updateMulti(new Query(Criteria.where("campaignId").is(paused)),
                    new Update().set("status", RecipientStatus.SKIPPED).set("skipReason", SkipReason.CANCELLED),
                    CampaignRecipient.class);
            mongo.updateFirst(new Query(Criteria.where("_id").is(paused)),
                    new Update().set("status", CampaignStatus.PAUSED).set("pausedBy", PausedBy.USER), Campaign.class);
            tickNow();
            assertThat(recipient(id, P1).getStatus()).isEqualTo(RecipientStatus.SENT);
            clock.advance(Duration.ofSeconds(10));
            tickNow();
            Campaign done = campaign(id);
            assertThat(done.getStatus()).isEqualTo(CampaignStatus.COMPLETED);
            assertThat(done.getFinishedAt()).isNotNull();
            assertThat(campaign(paused).getStatus()).isEqualTo(CampaignStatus.PAUSED);
            assertThat(campaign(paused).getFinishedAt()).isNull();
        }
    }

    @Test
    void csvCampaignRendersCellsAndNamesTheContact() throws Exception {
        try (AutoDevice d = device(n1)) {
            String t = template("City Hi", "Hi {{contact.name}} from {{city}}");
            String id = csvCampaign("Csv", "Phone,Name,City\n9811100001,Asha,Pune", t,
                    List.of(column("contact.name", "Name"), column("city", "City")));
            tickNow();
            Envelope send = d.requests("sendMessage").get(0);
            assertThat(send.params().get("content").asString()).isEqualTo("Hi Asha from Pune");
            CampaignRecipient r = recipient(id, P1);
            assertThat(r.getStatus()).isEqualTo(RecipientStatus.SENT);
            WaWebChat chat = mongo.findById(r.getChatId(), WaWebChat.class);
            assertThat(chat.getRemoteId()).isEqualTo("919811100001@c.us");
            Contact c = mongo.findOne(new Query(Criteria.where("workspaceId").is(brand.getId()).and("phone").is(P1)),
                    Contact.class);
            assertThat(c.getName()).isEqualTo("Asha");
            assertThat(c.isNameEdited()).isTrue();
            assertThat(r.getContactId()).isEqualTo(c.getId());
        }
    }

    @Test
    void renderedLinkFailsWithoutSending() throws Exception {
        try (AutoDevice d = device(n1)) {
            String t = template("City Hi", "Hi {{contact.name}} from {{city}}");
            String id = csvCampaign("Link", "Phone,Name,City\n9811100001,Asha,www.x.in", t,
                    List.of(column("contact.name", "Name"), column("city", "City")));
            tickNow();
            CampaignRecipient r = recipient(id, P1);
            assertThat(r.getStatus()).isEqualTo(RecipientStatus.FAILED);
            assertThat(r.getError()).isEqualTo("HAS_LINK");
            assertThat(d.requests("sendMessage")).isEmpty();
            assertThat(sender(n1).getNextSendAt()).isNull();
        }
    }

    @Test
    void twoSendersNeverMessageOnePhoneAtOnce() throws Exception {
        try (AutoDevice d1 = device(n1); AutoDevice d2 = device(n2)) {
            // the first lookup of P1 waits (up to a second) for a second one: two senders both holding P1
            CountDownLatch both = new CountDownLatch(2);
            Consumer<String> meet = phone -> {
                if (!P1.equals(phone)) return;
                both.countDown();
                try {
                    both.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            d1.onLookup(meet);
            d2.onLookup(meet);
            String t = templateNamed("City Hi", "Hi {{contact.name}} from {{city}}");
            List<Map<String, Object>> mapped = List.of(column("contact.name", "Name"), column("city", "City"));
            String a = csvCampaign("A", "Phone,Name,City\n9811100001,Asha,Pune\n9811100002,Bina,Pune", t, mapped, n1);
            String b = csvCampaign("B", "Phone,Name,City\n9811100001,Asha,Pune\n9811100003,Chitra,Pune", t, mapped,
                    n2);
            tickNow();
            assertThat(sendsTo(P1, d1, d2)).isEqualTo(1);

            clock.advance(Duration.ofSeconds(45));
            tickNow();
            assertThat(sendsTo(P1, d1, d2)).isEqualTo(1);
            CampaignRecipient ra = recipient(a, P1);
            CampaignRecipient rb = recipient(b, P1);
            assertThat(List.of(ra.getStatus(), rb.getStatus()))
                    .containsExactlyInAnyOrder(RecipientStatus.SENT, RecipientStatus.SKIPPED);
            CampaignRecipient skipped = ra.getStatus() == RecipientStatus.SKIPPED ? ra : rb;
            assertThat(skipped.getSkipReason()).isEqualTo(SkipReason.RECENTLY_MESSAGED);
            assertThat(sendsTo(P2, d1, d2) + sendsTo(P3, d1, d2)).isEqualTo(2);
        }
    }

    @Test
    void pauseBetweenTickAndSendReleasesTheClaim() throws Exception {
        try (AutoDevice d = device(n1)) {
            String id = startedFilterCampaign("Paused late", List.of(P1), n1);
            CountDownLatch gate = holdCampaignScheduler();
            CompletableFuture<Void> tick = runner.tick();
            mongo.updateFirst(new Query(Criteria.where("_id").is(id)),
                    new Update().set("status", CampaignStatus.PAUSED).set("pausedBy", PausedBy.USER), Campaign.class);
            gate.countDown();
            tick.get(10, TimeUnit.SECONDS);
            CampaignRecipient r = recipient(id, P1);
            assertThat(r.getStatus()).isEqualTo(RecipientStatus.PENDING);
            assertThat(r.getSenderId()).isNull();
            assertThat(r.getAttemptedAt()).isNull();
            assertThat(d.requests()).isEmpty();
            assertThat(sender(n1).getNextSendAt()).isNull();
        }
    }

    @Test
    void senderRetiredBetweenTickAndSendSendsNothing() throws Exception {
        try (AutoDevice d = device(n1)) {
            String id = startedFilterCampaign("Retired late", List.of(P1), n1);
            CountDownLatch gate = holdCampaignScheduler();
            CompletableFuture<Void> tick = runner.tick();
            api(HttpMethod.PUT, "/campaigns/senders/" + n1.getId(), manager, Map.of("action", "RETIRE"));
            gate.countDown();
            tick.get(10, TimeUnit.SECONDS);
            CampaignRecipient r = recipient(id, P1);
            assertThat(r.getStatus()).isEqualTo(RecipientStatus.PENDING);
            assertThat(r.getSenderId()).isNull();
            assertThat(r.getAttemptedAt()).isNull();
            assertThat(d.requests()).isEmpty();
            assertThat(sender(n1).getNextSendAt()).isNull();
        }
    }

    @Test
    void senderPausedBetweenTickAndSendSendsNothing() throws Exception {
        try (AutoDevice d = device(n1)) {
            String id = startedFilterCampaign("Paused sender", List.of(P1), n1);
            CountDownLatch gate = holdCampaignScheduler();
            CompletableFuture<Void> tick = runner.tick();
            sender(n1, s -> s.setNextSendAt(WED_11_IST.plusSeconds(30)));
            gate.countDown();
            tick.get(10, TimeUnit.SECONDS);
            assertThat(recipient(id, P1).getStatus()).isEqualTo(RecipientStatus.PENDING);
            assertThat(d.requests()).isEmpty();
        }
    }

    @Test
    void windowClosingBetweenTickAndSendReleasesTheClaim() throws Exception {
        try (AutoDevice d = device(n1)) {
            clock.set(Instant.parse("2026-10-21T12:29:59Z"));   // 17:59:59 IST
            String id = startedFilterCampaign("Late", List.of(P1), n1);
            CountDownLatch gate = holdCampaignScheduler();
            CompletableFuture<Void> tick = runner.tick();
            clock.set(Instant.parse("2026-10-21T12:30:00Z"));   // 18:00 IST
            gate.countDown();
            tick.get(10, TimeUnit.SECONDS);
            CampaignRecipient r = recipient(id, P1);
            assertThat(r.getStatus()).isEqualTo(RecipientStatus.PENDING);
            assertThat(r.getSenderId()).isNull();
            assertThat(d.requests()).isEmpty();
        }
    }

    @Test
    void warmingBecomesActiveAfterThirtySendingDays() throws Exception {
        startedFilterCampaign("Mature", List.of(P1), n1);
        sender(n1, s -> {
            s.setSendingDays(30);
            s.setLastSendingDay(LocalDate.parse("2026-10-20"));
        });
        assertThat(sender(n1).getState()).isEqualTo(SenderState.WARMING);
        tickNow();
        assertThat(sender(n1).getState()).isEqualTo(SenderState.ACTIVE);
    }

    @Test
    void tickRunsOnTheCampaignScheduler() throws Exception {
        ThreadPoolTaskScheduler campaignScheduler = context.getBean("campaignScheduler", ThreadPoolTaskScheduler.class);
        ThreadPoolTaskScheduler taskScheduler = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
        assertThat(campaignScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
        assertThat(campaignScheduler.getThreadNamePrefix()).isEqualTo("campaign-");
        assertThat(campaignScheduler.isDaemon()).isTrue();
        assertThat(ReflectionTestUtils.getField(campaignScheduler, "waitForTasksToCompleteOnShutdown")).isEqualTo(true);
        assertThat(ReflectionTestUtils.getField(campaignScheduler, "awaitTerminationMillis")).isEqualTo(75_000L);
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
        assertThat(context.getBeansOfType(CampaignTicker.class)).isEmpty();

        try (AutoDevice d = device(n1)) {
            startedFilterCampaign("Pool", List.of(P1), n1);
            long before = campaignScheduler.getScheduledThreadPoolExecutor().getTaskCount();
            tickNow();
            assertThat(d.requests("sendMessage")).hasSize(1);
            assertThat(campaignScheduler.getScheduledThreadPoolExecutor().getTaskCount()).isGreaterThan(before);
        }
    }
}
