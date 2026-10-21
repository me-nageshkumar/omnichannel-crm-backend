package me.nageshk.omnichannel.campaign;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.api.DuplicateException;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.contact.ContactCsv;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.template.MessageTemplate;
import me.nageshk.omnichannel.template.MessageTemplateRepository;
import me.nageshk.omnichannel.template.TemplatePlaceholder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Campaign drafts: create, edit and delete. */
@Service
public class CampaignService {

    /** An uploaded CSV audience, or null when the request carries no file. */
    public record CsvUpload(String fileName, byte[] bytes) {
    }

    private static final int SAMPLE_ROWS = 5;

    private final CampaignRepository repository;
    private final CampaignValidator validator;
    private final WorkspaceRepository workspaces;
    private final MongoTemplate mongo;
    private final Clock clock;
    private final CampaignCsvStore csvStore;
    private final MessageTemplateRepository templates;

    CampaignService(CampaignRepository repository, CampaignValidator validator, WorkspaceRepository workspaces,
            MongoTemplate mongo, Clock clock, CampaignCsvStore csvStore, MessageTemplateRepository templates) {
        this.csvStore = csvStore;
        this.templates = templates;
        this.repository = repository;
        this.validator = validator;
        this.workspaces = workspaces;
        this.mongo = mongo;
        this.clock = clock;
    }

    public Campaign create(AuthenticatedUser me, String workspaceId, CampaignRequest req, CsvUpload csv) {
        CampaignCsv.Parsed parsed = isCsv(req) && csv != null ? CampaignCsv.parse(csv.bytes()) : null;
        CampaignValidator.Validated v = validator.validate(workspaceId, req, parsed == null ? null : parsed.headers(),
                null);
        String fileId = null;
        if (parsed != null) {
            fileId = csvStore.store(workspaceId, csv.fileName(), csv.bytes());
            v = v.withAudience(withFile(v.audience(), csv.fileName(), parsed.rows().size(), fileId));
        }
        Campaign c = new Campaign();
        c.setWorkspaceId(workspaceId);
        c.setCustomerId(workspaces.findById(workspaceId).map(Workspace::getCustomerId).orElse(me.customerId()));
        c.setStatus(CampaignStatus.DRAFT);
        c.setCounts(Campaign.Counts.ZERO);
        c.setCreatedBy(me.getUsername());
        c.setCreationTime(clock.instant());
        apply(c, v);
        try {
            return save(c);
        } catch (RuntimeException failed) {
            csvStore.delete(fileId);
            throw failed;
        }
    }

    public Campaign update(String workspaceId, String id, CampaignRequest req, CsvUpload csv) {
        Campaign existing = require(workspaceId, id);
        Campaign.Audience old = existing.getAudience();
        CampaignCsv.Parsed parsed = isCsv(req) && csv != null ? CampaignCsv.parse(csv.bytes()) : null;
        boolean keepsFile = isCsv(req) && csv == null && old != null && old.kind() == AudienceKind.CSV;
        List<String> headers = parsed != null ? parsed.headers() : keepsFile ? old.csvHeaders() : null;
        CampaignValidator.Validated v = validator.validate(workspaceId, req, headers, id);
        String newFileId = null;
        if (parsed != null) {
            newFileId = csvStore.store(workspaceId, csv.fileName(), csv.bytes());
            v = v.withAudience(withFile(v.audience(), csv.fileName(), parsed.rows().size(), newFileId));
        } else if (keepsFile) {
            v = v.withAudience(withFile(v.audience(), old.csvFileName(), old.csvRows(), old.csvFileId()));
        }
        Update set = new Update().set("name", v.name()).set("nameKey", CampaignValidator.nameKey(v.name()))
                .set("audience", v.audience())
                .set("templateIds", v.templates().stream().map(MessageTemplate::getId).toList())
                .set("templateNames", v.templates().stream().map(MessageTemplate::getName).toList())
                .set("placeholders", v.placeholders()).set("senderIds", v.senderIds())
                .set("lastUpdateTime", clock.instant()).inc("version", 1);
        long matched;
        try {
            matched = mongo.updateFirst(draft(workspaceId, id), set, Campaign.class).getMatchedCount();
        } catch (DuplicateKeyException raced) {
            csvStore.delete(newFileId);
            throw new DuplicateException("name", "a campaign with this name already exists");
        }
        if (matched == 0) {
            csvStore.delete(newFileId);
            throw CampaignErrors.notEditable();
        }
        if (old != null && old.csvFileId() != null && !keepsFile) csvStore.delete(old.csvFileId());
        return require(workspaceId, id);
    }

    public void delete(String workspaceId, String id) {
        Campaign c = require(workspaceId, id);
        if (mongo.remove(draft(workspaceId, id), Campaign.class).getDeletedCount() == 0) {
            throw CampaignErrors.notEditable();
        }
        if (c.getAudience() != null) csvStore.delete(c.getAudience().csvFileId());
    }

    /** The first rows and suggested column mappings of an uploaded file; nothing is stored. */
    public CsvPreview preview(String workspaceId, byte[] file, List<String> templateIds) {
        CampaignCsv.Parsed parsed = CampaignCsv.parse(file);
        String phone = parsed.headers().stream().filter(h -> h.equalsIgnoreCase("phone")).findFirst().orElse(null);
        Map<String, String> suggested = new LinkedHashMap<>();
        for (MessageTemplate t : templatesOf(workspaceId, templateIds)) {
            for (TemplatePlaceholder p : t.getPlaceholders()) {
                parsed.headers().stream().filter(h -> h.equalsIgnoreCase(p.getName())).findFirst()
                        .ifPresent(h -> suggested.putIfAbsent(p.getName(), h));
            }
        }
        return new CsvPreview(parsed.headers(), parsed.rows().stream().limit(SAMPLE_ROWS).toList(),
                parsed.rows().size(), new CsvPreview.Suggested(phone, suggested));
    }

    /** A CSV a user can fill in: {@code phone} and each placeholder of the template, with one example row. */
    public String sampleCsv(String workspaceId, String templateId) {
        MessageTemplate t = templates.findByIdAndWorkspaceId(templateId, workspaceId)
                .orElseThrow(() -> new NotFoundException("template not found"));
        StringBuilder head = new StringBuilder("phone");
        StringBuilder row = new StringBuilder("919812345678");
        for (TemplatePlaceholder p : t.getPlaceholders()) {
            head.append(',').append(ContactCsv.cell(p.getName()));
            row.append(',').append(ContactCsv.cell(p.getExample()));
        }
        return head + "\r\n" + row + "\r\n";
    }

    private List<MessageTemplate> templatesOf(String workspaceId, List<String> ids) {
        List<MessageTemplate> out = new ArrayList<>();
        for (String id : ids == null ? List.<String>of() : ids) {
            out.add(templates.findByIdAndWorkspaceId(id, workspaceId)
                    .orElseThrow(() -> new ValidationException("templateIds", "unknown template")));
        }
        return out;
    }

    private static boolean isCsv(CampaignRequest req) {
        return req.audience() != null && req.audience().kind() == AudienceKind.CSV;
    }

    private static Campaign.Audience withFile(Campaign.Audience a, String fileName, Integer rows, String fileId) {
        return new Campaign.Audience(a.kind(), a.filterId(), a.filterName(), a.filterSpec(), fileName, a.csvHeaders(),
                rows, a.phoneColumn(), a.nameColumn(), fileId);
    }

    /** The campaign only while it is still a draft, so a start that wins the race is never overwritten. */
    private static Query draft(String workspaceId, String id) {
        return new Query(Criteria.where("_id").is(id).and("workspaceId").is(workspaceId)
                .and("status").is(CampaignStatus.DRAFT));
    }

    public Campaign require(String workspaceId, String id) {
        return repository.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new NotFoundException("campaign not found"));
    }

    private static void apply(Campaign c, CampaignValidator.Validated v) {
        c.setName(v.name());
        c.setNameKey(CampaignValidator.nameKey(v.name()));
        c.setAudience(v.audience());
        c.setTemplateIds(v.templates().stream().map(MessageTemplate::getId).toList());
        c.setTemplateNames(v.templates().stream().map(MessageTemplate::getName).toList());
        c.setPlaceholders(v.placeholders());
        c.setSenderIds(v.senderIds());
    }

    private Campaign save(Campaign c) {
        try {
            return repository.save(c);
        } catch (DuplicateKeyException raced) {
            throw new DuplicateException("name", "a campaign with this name already exists");
        }
    }
}
