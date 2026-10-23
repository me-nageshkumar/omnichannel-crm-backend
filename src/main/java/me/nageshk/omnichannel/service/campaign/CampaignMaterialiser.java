package me.nageshk.omnichannel.service.campaign;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.db.campaign.AudienceKind;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignCsvStore;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.CampaignSettings;
import me.nageshk.omnichannel.db.campaign.RecipientSkips;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import me.nageshk.omnichannel.db.campaign.SkipReason;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.FilterKind;
import me.nageshk.omnichannel.service.contact.ContactQuery;
import me.nageshk.omnichannel.service.contact.Phones;
import me.nageshk.omnichannel.service.contact.QueryTimeouts;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** Writes a campaign's recipients, a page of at most 500 source rows at a time. */
@Component
public class CampaignMaterialiser {

    static final int PAGE = 500;

    private final MongoTemplate mongo;
    private final ContactQuery contactQuery;
    private final CampaignCsvStore csvStore;
    private final RecipientSkips skips;
    private final QueryTimeouts timeouts;

    CampaignMaterialiser(MongoTemplate mongo, ContactQuery contactQuery, CampaignCsvStore csvStore,
            RecipientSkips skips, QueryTimeouts timeouts) {
        this.mongo = mongo;
        this.contactQuery = contactQuery;
        this.csvStore = csvStore;
        this.skips = skips;
        this.timeouts = timeouts;
    }

    /** One source row: the raw phone cell (or contact phone), its contact when known, and the CSV cells. */
    private record Row(String raw, Contact contact, List<String> values) {
    }

    private static final class Run {
        final Campaign campaign;
        final CampaignSettings settings;
        final Instant now;
        final Set<String> seen = new HashSet<>();
        int next;
        int written;

        Run(Campaign campaign, CampaignSettings settings, Instant now) {
            this.campaign = campaign;
            this.settings = settings;
            this.now = now;
        }
    }

    /** Returns the rows written; on failure the campaign's recipients are deleted and the failure rethrown. */
    int materialise(Campaign c, CampaignSettings s, Instant now) {
        Run run = new Run(c, s, now);
        try {
            if (c.getAudience().kind() == AudienceKind.CSV) csv(run); else filter(run);
            return run.written;
        } catch (RuntimeException failed) {
            mongo.remove(new Query(Criteria.where("campaignId").is(c.getId())), CampaignRecipient.class);
            throw failed;
        }
    }

    private void filter(Run run) {
        String ws = run.campaign.getWorkspaceId();
        Campaign.Audience audience = run.campaign.getAudience();
        Criteria base = audience.filterKind() == FilterKind.ADVANCED
                ? contactQuery.advanced(ws, audience.filterQueryJson())
                : contactQuery.build(ws, audience.filterSpec());
        String lastId = null;
        while (true) {
            List<Criteria> all = new ArrayList<>(List.of(base, Criteria.where("phone").ne(null)));
            if (lastId != null) all.add(Criteria.where("_id").gt(lastId));
            Query q = new Query(new Criteria().andOperator(all)).with(Sort.by("_id")).limit(PAGE)
                    .maxTime(timeouts.materialise());
            List<Contact> page = QueryTimeouts.guarded(() -> mongo.find(q, Contact.class));
            if (page.isEmpty()) return;
            lastId = page.get(page.size() - 1).getId();
            write(run, page.stream().map(c -> new Row(c.getPhone(), c, null)).toList(), page);
            if (page.size() < PAGE) return;
        }
    }

    private void csv(Run run) {
        Campaign.Audience a = run.campaign.getAudience();
        CampaignCsv.Parsed parsed = CampaignCsv.parse(csvStore.read(a.csvFileId()));
        int phoneAt = parsed.headers().indexOf(a.phoneColumn());
        List<List<String>> rows = parsed.rows();
        for (int from = 0; from < rows.size(); from += PAGE) {
            List<Row> page = new ArrayList<>();
            for (List<String> cells : rows.subList(from, Math.min(rows.size(), from + PAGE))) {
                page.add(new Row(cells.get(phoneAt), null, cells));
            }
            write(run, page, null);
        }
    }

    /** Judges the page's rows in order and inserts them all with one write. */
    private void write(Run run, List<Row> rows, List<Contact> known) {
        Campaign c = run.campaign;
        boolean fromCsv = known == null;
        List<String> phones = new ArrayList<>();
        for (Row r : rows) {
            String phone = phoneOf(r, run, fromCsv);
            if (phone != null && Phones.isValid(phone)) phones.add(phone);
        }
        RecipientSkips.Page page = skips.load(c.getWorkspaceId(), phones, known, run.now,
                run.settings.getRecontactDays());
        List<CampaignRecipient> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            String phone = phoneOf(r, run, fromCsv);
            CampaignRecipient rec = new CampaignRecipient();
            rec.setCampaignId(c.getId());
            rec.setWorkspaceId(c.getWorkspaceId());
            rec.setRawPhone(r.raw());
            rec.setValues(r.values());
            rec.setStatus(RecipientStatus.SKIPPED);
            out.add(rec);
            if (phone == null || phone.isEmpty()) {
                rec.setSkipReason(SkipReason.NO_PHONE);
                continue;
            }
            if (!Phones.isValid(phone)) {
                rec.setSkipReason(SkipReason.INVALID_PHONE);
                continue;
            }
            if (!run.seen.add(phone)) {
                rec.setSkipReason(SkipReason.DUPLICATE);
                continue;
            }
            rec.setPhone(phone);
            Contact contact = r.contact() != null ? r.contact() : page.contactsByPhone().get(phone);
            if (contact != null) rec.setContactId(contact.getId());
            SkipReason skip = skips.reason(page, phone, contact, c.getSenderIds());
            if (skip != null) {
                rec.setSkipReason(skip);
                continue;
            }
            rec.setStatus(RecipientStatus.PENDING);
            rec.setPreferredSenderId(skips.preferredSender(page.chatsOf(contact), c.getSenderIds()));
            rec.setTemplateId(c.getTemplateIds().get(run.next++ % c.getTemplateIds().size()));
        }
        if (!out.isEmpty()) mongo.insertAll(out);
        run.written += out.size();
    }

    private static String phoneOf(Row r, Run run, boolean fromCsv) {
        String raw = r.raw() == null ? "" : r.raw().trim();
        String digits = Phones.normalise(raw);
        return fromCsv ? Phones.withDefaultCountryCode(digits, run.settings.getDefaultCountryCode()) : digits;
    }
}
