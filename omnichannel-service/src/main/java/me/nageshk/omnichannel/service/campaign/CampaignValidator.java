package me.nageshk.omnichannel.service.campaign;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.common.api.DuplicateException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.campaign.AudienceKind;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignCloudTemplate;
import me.nageshk.omnichannel.db.campaign.CampaignRepository;
import me.nageshk.omnichannel.db.campaign.PlaceholderSourceKind;
import me.nageshk.omnichannel.db.campaign.SenderState;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.contact.ContactFilter;
import me.nageshk.omnichannel.db.contact.ContactFilterRepository;
import me.nageshk.omnichannel.db.contact.FilterKind;
import me.nageshk.omnichannel.db.phone.ConnectionType;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.db.template.MessageTemplate;
import me.nageshk.omnichannel.db.template.MessageTemplateRepository;
import me.nageshk.omnichannel.db.template.PlaceholderKind;
import me.nageshk.omnichannel.db.template.TemplatePlaceholder;
import me.nageshk.omnichannel.db.template.TemplateStatus;
import me.nageshk.omnichannel.db.template.TemplateType;
import me.nageshk.omnichannel.meta.api.CloudTemplateInfo;
import me.nageshk.omnichannel.meta.api.CloudTemplates;
import org.springframework.stereotype.Component;

/** Checks a campaign request group by group; the first failing group is the one reported. */
@Component
public class CampaignValidator {

    static final int MAX_NAME = 100;
    static final int MAX_TEXT = 1000;
    static final int MAX_TEMPLATES = 5;

    /** What a valid request resolved to: the cleaned name and audience, the templates, mappings and senders. */
    public record Validated(String name, Campaign.Audience audience, List<MessageTemplate> templates,
            List<Campaign.PlaceholderMapping> placeholders, List<String> senderIds, ConnectionType channel,
            CampaignCloudTemplate cloudTemplate) {

        Validated withAudience(Campaign.Audience a) {
            return new Validated(name, a, templates, placeholders, senderIds, channel, cloudTemplate);
        }
    }

    /** The Cloud numbers of a request and their one WhatsApp account. */
    private record CloudSenders(List<String> ids, String wabaId) {
    }

    private final CampaignRepository campaigns;
    private final ContactFilterRepository filters;
    private final MessageTemplateRepository templates;
    private final ContactAttributeRepository attributes;
    private final PhoneNumberRepository numbers;
    private final CampaignSenderService senders;
    private final SegmentAudiences segments;
    private final CloudTemplates cloudTemplates;
    private final CloudCampaignSenders cloudSenders;

    CampaignValidator(CampaignRepository campaigns, ContactFilterRepository filters, MessageTemplateRepository templates,
            ContactAttributeRepository attributes, PhoneNumberRepository numbers, CampaignSenderService senders,
            SegmentAudiences segments, CloudTemplates cloudTemplates, CloudCampaignSenders cloudSenders) {
        this.segments = segments;
        this.cloudTemplates = cloudTemplates;
        this.cloudSenders = cloudSenders;
        this.campaigns = campaigns;
        this.filters = filters;
        this.templates = templates;
        this.attributes = attributes;
        this.numbers = numbers;
        this.senders = senders;
    }

    /**
     * {@code csvHeaders} is null for a filter audience; {@code excludeId} is the campaign being edited, or null,
     * so that it does not clash with its own name.
     */
    Validated validate(String workspaceId, CampaignRequest req, List<String> csvHeaders, String excludeId) {
        if (req.channel() == ConnectionType.WHATSAPP_CLOUD_API) return validateCloud(workspaceId, req, csvHeaders,
                excludeId);
        if (req.cloudTemplate() != null) {
            throw new ValidationException("cloudTemplate", "only campaigns on Cloud API numbers send WhatsApp templates");
        }
        String name = name(workspaceId, req.name(), excludeId);
        Campaign.Audience audience = audience(workspaceId, req.audience(), csvHeaders);
        List<MessageTemplate> chosen = templates(workspaceId, req.templateIds());
        List<Campaign.PlaceholderMapping> mappings = placeholders(workspaceId, chosen, req.placeholders(), csvHeaders);
        List<String> senderIds = senders(workspaceId, req.senderIds());
        return new Validated(name, audience, chosen, mappings, senderIds, ConnectionType.WHATSAPP_WEB, null);
    }

    /** A campaign on Cloud API numbers (spec 2026-11-21 §5.7): one WhatsApp account, one approved Meta template. */
    private Validated validateCloud(String workspaceId, CampaignRequest req, List<String> csvHeaders,
            String excludeId) {
        String name = name(workspaceId, req.name(), excludeId);
        Campaign.Audience audience = audience(workspaceId, req.audience(), csvHeaders);
        if (req.templateIds() != null && !req.templateIds().isEmpty()) {
            throw new ValidationException("templateIds", "a Cloud campaign sends a WhatsApp template, not saved templates");
        }
        CloudSenders numbersOf = cloudSenders(workspaceId, req.senderIds());
        CloudTemplateInfo t = cloudTemplate(workspaceId, req.cloudTemplate(), numbersOf.wabaId());
        List<Campaign.PlaceholderMapping> mappings = cloudPlaceholders(workspaceId, t, req.placeholders(), csvHeaders);
        String headerMediaId = req.cloudTemplate().headerMediaId();
        headerMediaId = headerMediaId == null || headerMediaId.isBlank() ? null : headerMediaId.strip();
        if (t.header().media() && headerMediaId == null) {
            throw new ValidationException("cloudTemplate.headerMediaId",
                    "a file for the template's " + t.header().format().toLowerCase() + " header");
        }
        if (!t.header().media()) headerMediaId = null;
        return new Validated(name, audience, List.of(), mappings, numbersOf.ids(), ConnectionType.WHATSAPP_CLOUD_API,
                new CampaignCloudTemplate(t.id(), t.name(), t.language(), headerMediaId));
    }

    private CloudSenders cloudSenders(String workspaceId, List<String> raw) {
        List<String> ids = raw == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(raw));
        if (ids.isEmpty()) throw new ValidationException("senderIds", "required");
        Map<String, PhoneNumber> mine = numbers.findByWorkspaceIdOrderByNumber(workspaceId).stream()
                .collect(Collectors.toMap(PhoneNumber::getId, Function.identity()));
        Set<String> wabas = new LinkedHashSet<>();
        for (String id : ids) {
            PhoneNumber n = mine.get(id);
            if (n == null) throw new ValidationException("senderIds", "unknown number");
            if (n.getDisabled() != null || n.getConnectionType() != ConnectionType.WHATSAPP_CLOUD_API
                    || !cloudSenders.canSend(id)) {
                throw CampaignErrors.senderNotAvailable(n.getNumber());
            }
            cloudSenders.wabaOf(id).ifPresent(wabas::add);
        }
        if (wabas.size() != 1) throw new ValidationException("senderIds", "Choose numbers of one WhatsApp account");
        return new CloudSenders(ids, wabas.iterator().next());
    }

    private CloudTemplateInfo cloudTemplate(String workspaceId, CampaignRequest.CloudTemplateChoice choice,
            String wabaId) {
        String field = "cloudTemplate.cloudTemplateId";
        if (choice == null || choice.cloudTemplateId() == null || choice.cloudTemplateId().isBlank()) {
            throw new ValidationException(field, "required");
        }
        CloudTemplateInfo t;
        try {
            t = cloudTemplates.get(workspaceId, choice.cloudTemplateId().strip());
        } catch (NotFoundException e) {
            throw new ValidationException(field, "unknown WhatsApp template");
        }
        if (!t.sendable()) {
            throw new ValidationException(field, "This WhatsApp template is not approved, or not supported yet");
        }
        if (!wabaId.equals(t.wabaId())) {
            throw new ValidationException(field, "This template belongs to another WhatsApp account");
        }
        return t;
    }

    /**
     * One mapping per blank of the template that the request maps; a blank left out takes the template's own fill
     * when sent, so it needs a contact field or attribute fill (an agent fill has no value in a campaign).
     */
    private List<Campaign.PlaceholderMapping> cloudPlaceholders(String workspaceId, CloudTemplateInfo t,
            List<Campaign.PlaceholderMapping> supplied, List<String> csvHeaders) {
        Map<String, Campaign.PlaceholderMapping> given = new LinkedHashMap<>();
        if (supplied != null) {
            for (Campaign.PlaceholderMapping m : supplied) if (m != null && m.name() != null) given.put(m.name(), m);
        }
        List<Campaign.PlaceholderMapping> out = new ArrayList<>();
        Set<String> columns = new HashSet<>();
        for (CloudTemplateInfo.Variable v : t.variables()) {
            String field = "placeholders." + v.key();
            Campaign.PlaceholderMapping m = given.get(v.key());
            if (m == null || m.source() == null) {
                CloudTemplateInfo.Fill fill = t.fills() == null ? null : t.fills().get(v.key());
                if (fill == null || fill.kind() == null || fill.kind() == CloudTemplateInfo.FillKind.AGENT) {
                    throw new ValidationException(field, "needs a value");
                }
                continue;
            }
            String fallback = m.fallback() == null ? "" : m.fallback();
            if (fallback.length() > MAX_TEXT) throw new ValidationException(field, "fallback is too long");
            Campaign.PlaceholderSource source = csvHeaders != null ? csvSource(m, csvHeaders)
                    : checked(workspaceId, m.source());
            if (source == null) throw new ValidationException(field, "needs a value");
            if (source.kind() == PlaceholderSourceKind.CSV_COLUMN && !columns.add(source.column())) {
                throw CampaignErrors.columnReused();
            }
            out.add(new Campaign.PlaceholderMapping(v.key(), source, fallback));
        }
        return out;
    }

    static String nameKey(String name) {
        return name.trim().toLowerCase();
    }

    private String name(String workspaceId, String raw, String excludeId) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new ValidationException("name", "must be 1 to 100 characters");
        }
        Optional<Campaign> same = campaigns.findByWorkspaceIdAndNameKey(workspaceId, nameKey(name));
        if (same.isPresent() && !same.get().getId().equals(excludeId)) {
            throw new DuplicateException("name", "a campaign with this name already exists");
        }
        return name;
    }

    private Campaign.Audience audience(String workspaceId, CampaignRequest.AudienceRequest a, List<String> csvHeaders) {
        if (a == null || a.kind() == null) throw new ValidationException("audience.kind", "unsupported");
        if (a.kind() == AudienceKind.CSV) return csvAudience(a, csvHeaders);
        if (a.kind() == AudienceKind.SEGMENT) return segmentAudience(workspaceId, a);
        if (a.kind() != AudienceKind.FILTER) throw new ValidationException("audience.kind", "unsupported");
        if (a.filterId() == null || a.filterId().isBlank()) {
            throw new ValidationException("audience.filterId", "required");
        }
        ContactFilter f = filters.findByIdAndWorkspaceId(a.filterId(), workspaceId)
                .orElseThrow(() -> new ValidationException("audience.filterId", "unknown filter"));
        return new Campaign.Audience(AudienceKind.FILTER, f.getId(), f.getName(), f.getFilter(), null, null, null,
                null, null, null, FilterKind.orSimple(f.getKind()), f.getQueryJson(), f.getUses(), null, null);
    }

    /** A CRM segment, snapshotted by id and name; its members are resolved when the campaign starts. */
    private Campaign.Audience segmentAudience(String workspaceId, CampaignRequest.AudienceRequest a) {
        if (a.segmentId() == null || a.segmentId().isBlank()) {
            throw new ValidationException("audience.segmentId", "required");
        }
        SegmentAudiences.SegmentRef ref = segments.find(workspaceId, a.segmentId())
                .orElseThrow(() -> new ValidationException("audience.segmentId", "unknown segment"));
        return new Campaign.Audience(AudienceKind.SEGMENT, null, null, null, null, null, null, null, null, null, null,
                null, null, ref.id(), ref.name());
    }

    /** The CSV audience's columns; the file's name, row count and id are filled in by the service. */
    private static Campaign.Audience csvAudience(CampaignRequest.AudienceRequest a, List<String> headers) {
        if (headers == null) throw new ValidationException("file", "required");
        String phone = a.phoneColumn() == null ? "" : a.phoneColumn().trim();
        if (phone.isEmpty()) throw CampaignErrors.phoneColumnRequired();
        if (!headers.contains(phone)) throw new ValidationException("audience.phoneColumn", "is not a column of the file");
        String name = a.nameColumn() == null ? "" : a.nameColumn().trim();
        if (!name.isEmpty() && !headers.contains(name)) {
            throw new ValidationException("audience.nameColumn", "is not a column of the file");
        }
        return new Campaign.Audience(AudienceKind.CSV, null, null, null, null, headers, null, phone,
                name.isEmpty() ? null : name, null, null, null, null, null, null);
    }

    private List<MessageTemplate> templates(String workspaceId, List<String> raw) {
        List<String> ids = raw == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(raw));
        if (ids.isEmpty() || ids.size() > MAX_TEMPLATES) {
            throw new ValidationException("templateIds", "needs 1 to 5 templates");
        }
        List<MessageTemplate> chosen = new ArrayList<>();
        for (String id : ids) {
            MessageTemplate t = templates.findByIdAndWorkspaceId(id, workspaceId)
                    .orElseThrow(() -> new ValidationException("templateIds", "unknown template"));
            if (t.getStatus() != TemplateStatus.ACTIVE) throw new ValidationException("templateIds", "archived");
            chosen.add(t);
        }
        if (chosen.stream().anyMatch(t -> t.getType() != TemplateType.TEXT)) throw CampaignErrors.templateNotText();
        if (chosen.stream().anyMatch(t -> t.getContent() != null && CampaignLinks.containsLink(t.getContent().getBody()))) {
            throw CampaignErrors.templateHasLink("templateIds");
        }
        return chosen;
    }

    private List<Campaign.PlaceholderMapping> placeholders(String workspaceId, List<MessageTemplate> chosen,
            List<Campaign.PlaceholderMapping> supplied, List<String> csvHeaders) {
        Map<String, TemplatePlaceholder> used = new LinkedHashMap<>();
        for (MessageTemplate t : chosen) {
            for (TemplatePlaceholder p : t.getPlaceholders()) used.putIfAbsent(p.getName(), p);
        }
        Map<String, Campaign.PlaceholderMapping> given = new LinkedHashMap<>();
        if (supplied != null) {
            for (Campaign.PlaceholderMapping m : supplied) if (m != null && m.name() != null) given.put(m.name(), m);
        }
        List<Campaign.PlaceholderMapping> out = new ArrayList<>();
        for (TemplatePlaceholder p : used.values()) {
            Campaign.PlaceholderMapping m = given.get(p.getName());
            String fallback = m == null || m.fallback() == null ? "" : m.fallback();
            if (fallback.length() > MAX_TEXT) throw new ValidationException("placeholders", "fallback is too long");
            if (CampaignLinks.containsLink(fallback)) throw CampaignErrors.templateHasLink("placeholders");
            Campaign.PlaceholderSource source = csvHeaders != null ? csvSource(m, csvHeaders)
                    : m == null || m.source() == null ? defaultSource(p) : checked(workspaceId, m.source());
            if (source == null) throw CampaignErrors.placeholderUnmapped();
            out.add(new Campaign.PlaceholderMapping(p.getName(), source, fallback));
        }
        if (csvHeaders != null) {
            Set<String> columns = new HashSet<>();
            for (Campaign.PlaceholderMapping m : out) {
                if (m.source().kind() == PlaceholderSourceKind.CSV_COLUMN && !columns.add(m.source().column())) {
                    throw CampaignErrors.columnReused();
                }
            }
        }
        return out;
    }

    /** A CSV audience takes a column of the file or fixed text; there is no contact to read from. */
    private static Campaign.PlaceholderSource csvSource(Campaign.PlaceholderMapping m, List<String> headers) {
        Campaign.PlaceholderSource s = m == null ? null : m.source();
        if (s == null || s.kind() == null) return null;
        return switch (s.kind()) {
            case CSV_COLUMN -> s.column() != null && headers.contains(s.column().trim())
                    ? new Campaign.PlaceholderSource(s.kind(), null, null, s.column().trim(), null) : null;
            case FIXED -> fixed(s);
            default -> null;
        };
    }

    /** What a contact placeholder takes without being told; an agent placeholder has no default. */
    private static Campaign.PlaceholderSource defaultSource(TemplatePlaceholder p) {
        if (p.getKind() == PlaceholderKind.CONTACT_FIELD) {
            String field = p.getName().equals("contact.phone") ? "phone" : "name";
            return new Campaign.PlaceholderSource(PlaceholderSourceKind.CONTACT_FIELD, field, null, null, null);
        }
        if (p.getKind() == PlaceholderKind.CONTACT_ATTRIBUTE && p.getAttributeId() != null) {
            return new Campaign.PlaceholderSource(PlaceholderSourceKind.CONTACT_ATTRIBUTE, null, p.getAttributeId(),
                    null, null);
        }
        return null;
    }

    /** The source cleaned to the fields of its kind, or null when it is not allowed for a filter audience. */
    private Campaign.PlaceholderSource checked(String workspaceId, Campaign.PlaceholderSource s) {
        if (s.kind() == null) return null;
        return switch (s.kind()) {
            case CONTACT_FIELD -> "name".equals(s.field()) || "phone".equals(s.field())
                    ? new Campaign.PlaceholderSource(s.kind(), s.field(), null, null, null) : null;
            case CONTACT_ATTRIBUTE -> s.attributeId() != null
                    && attributes.findByIdAndWorkspaceId(s.attributeId(), workspaceId).isPresent()
                    ? new Campaign.PlaceholderSource(s.kind(), null, s.attributeId(), null, null) : null;
            case FIXED -> fixed(s);
            case CSV_COLUMN -> null;
        };
    }

    private static Campaign.PlaceholderSource fixed(Campaign.PlaceholderSource s) {
        if (s.text() == null || s.text().isBlank() || s.text().length() > MAX_TEXT) return null;
        if (CampaignLinks.containsLink(s.text())) throw CampaignErrors.templateHasLink("placeholders");
        return new Campaign.PlaceholderSource(s.kind(), null, null, null, s.text());
    }

    private List<String> senders(String workspaceId, List<String> raw) {
        List<String> ids = raw == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(raw));
        if (ids.isEmpty()) throw new ValidationException("senderIds", "required");
        Map<String, PhoneNumber> mine = numbers.findByWorkspaceIdOrderByNumber(workspaceId).stream()
                .collect(Collectors.toMap(PhoneNumber::getId, Function.identity()));
        for (String id : ids) {
            if (!mine.containsKey(id)) throw new ValidationException("senderIds", "unknown number");
        }
        for (String id : ids) {
            PhoneNumber n = mine.get(id);
            boolean usable = n.getDisabled() == null && n.getConnectionType() != ConnectionType.WHATSAPP_CLOUD_API
                    && isSending(senders.ensure(workspaceId, id).getState());
            if (!usable) throw CampaignErrors.senderNotAvailable(n.getNumber());
        }
        return ids;
    }

    private static boolean isSending(SenderState state) {
        return state == SenderState.WARMING || state == SenderState.ACTIVE;
    }
}
