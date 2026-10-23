package me.nageshk.omnichannel.service.campaign;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.CampaignStatus;
import me.nageshk.omnichannel.db.campaign.PausedBy;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** The read side: the campaign list and a campaign's recipients, 50 to a page. */
@Service
public class CampaignQueryService {

    static final int PAGE_SIZE = 50;

    private final MongoTemplate mongo;
    private final CampaignViews views;
    private final ContactRepository contacts;
    private final PhoneNumberRepository phoneNumbers;

    CampaignQueryService(MongoTemplate mongo, CampaignViews views, ContactRepository contacts,
            PhoneNumberRepository phoneNumbers) {
        this.mongo = mongo;
        this.views = views;
        this.contacts = contacts;
        this.phoneNumbers = phoneNumbers;
    }

    public record CampaignPage(List<CampaignView> items, long total, int page, boolean systemPaused) {
    }

    public record RecipientPage(List<RecipientView> items, long total, int page) {
    }

    public CampaignPage list(String workspaceId, CampaignStatus status, int page) {
        Criteria where = Criteria.where("workspaceId").is(workspaceId);
        if (status != null) where.and("status").is(status);
        long total = mongo.count(new Query(where), Campaign.class);
        List<CampaignView> items = mongo.find(paged(new Query(where), page)
                .with(Sort.by(Sort.Order.desc("creationTime"), Sort.Order.desc("_id"))), Campaign.class).stream()
                .map(views::summary).toList();
        boolean systemPaused = mongo.exists(new Query(Criteria.where("workspaceId").is(workspaceId)
                .and("status").is(CampaignStatus.PAUSED).and("pausedBy").is(PausedBy.SYSTEM)), Campaign.class);
        return new CampaignPage(items, total, page, systemPaused);
    }

    public RecipientPage recipients(String campaignId, RecipientStatus status, int page) {
        Criteria where = Criteria.where("campaignId").is(campaignId);
        if (status != null) where.and("status").is(status);
        long total = mongo.count(new Query(where), CampaignRecipient.class);
        List<CampaignRecipient> rows = mongo.find(paged(new Query(where), page).with(Sort.by("_id")),
                CampaignRecipient.class);
        Set<String> contactIds = rows.stream().map(CampaignRecipient::getContactId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> senderIds = rows.stream().map(CampaignRecipient::getSenderId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> names = new HashMap<>();
        for (Contact c : contacts.findAllById(contactIds)) names.put(c.getId(), c.getName());
        Map<String, String> numbers = new HashMap<>();
        for (PhoneNumber n : phoneNumbers.findAllById(senderIds)) numbers.put(n.getId(), n.getNumber());
        List<RecipientView> items = rows.stream().map(r -> new RecipientView(r.getId(),
                r.getPhone() != null ? r.getPhone() : r.getRawPhone(), r.getContactId(), names.get(r.getContactId()),
                r.getStatus(), r.getSkipReason(), r.getSenderId(), numbers.get(r.getSenderId()), r.getTemplateId(),
                r.getAttemptedAt(), r.getSentAt(), r.getDeliveredAt(), r.getReadAt(), r.getRepliedAt(), r.getError(),
                r.getAttempts(), r.getDispatchedAt())).toList();
        return new RecipientPage(items, total, page);
    }

    private static Query paged(Query q, int page) {
        return q.skip((long) Math.max(page, 0) * PAGE_SIZE).limit(PAGE_SIZE);
    }
}
