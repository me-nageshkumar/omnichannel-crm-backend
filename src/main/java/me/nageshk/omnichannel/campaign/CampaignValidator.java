package me.nageshk.omnichannel.campaign;

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
import me.nageshk.omnichannel.api.DuplicateException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.contact.ContactFilter;
import me.nageshk.omnichannel.contact.ContactFilterRepository;
import me.nageshk.omnichannel.contact.FilterKind;
import me.nageshk.omnichannel.phone.ConnectionType;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.template.MessageTemplate;
import me.nageshk.omnichannel.template.MessageTemplateRepository;
import me.nageshk.omnichannel.template.PlaceholderKind;
import me.nageshk.omnichannel.template.TemplatePlaceholder;
import me.nageshk.omnichannel.template.TemplateStatus;
import me.nageshk.omnichannel.template.TemplateType;
import org.springframework.stereotype.Component;

/** Checks a campaign request group by group; the first failing group is the one reported. */
@Component
public class CampaignValidator {

    static final int MAX_NAME = 100;
    static final int MAX_TEXT = 1000;
    static final int MAX_TEMPLATES = 5;

    /** What a valid request resolved to: the cleaned name and audience, the templates, mappings and senders. */
    public record Validated(String name, Campaign.Audience audience, List<MessageTemplate> templates,
            List<Campaign.PlaceholderMapping> placeholders, List<String> senderIds) {

        Validated withAudience(Campaign.Audience a) {
            return new Validated(name, a, templates, placeholders, senderIds);
        }
    }

    private final CampaignRepository campaigns;
    private final ContactFilterRepository filters;
    private final MessageTemplateRepository templates;
    private final ContactAttributeRepository attributes;
    private final PhoneNumberRepository numbers;
    private final CampaignSenderService senders;

    CampaignValidator(CampaignRepository campaigns, ContactFilterRepository filters, MessageTemplateRepository templates,
            ContactAttributeRepository attributes, PhoneNumberRepository numbers, CampaignSenderService senders) {
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
        String name = name(workspaceId, req.name(), excludeId);
        Campaign.Audience audience = audience(workspaceId, req.audience(), csvHeaders);
        List<MessageTemplate> chosen = templates(workspaceId, req.templateIds());
        List<Campaign.PlaceholderMapping> mappings = placeholders(workspaceId, chosen, req.placeholders(), csvHeaders);
        List<String> senderIds = senders(workspaceId, req.senderIds());
        return new Validated(name, audience, chosen, mappings, senderIds);
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
        if (a.kind() != AudienceKind.FILTER) throw new ValidationException("audience.kind", "unsupported");
        if (a.filterId() == null || a.filterId().isBlank()) {
            throw new ValidationException("audience.filterId", "required");
        }
        ContactFilter f = filters.findByIdAndWorkspaceId(a.filterId(), workspaceId)
                .orElseThrow(() -> new ValidationException("audience.filterId", "unknown filter"));
        return new Campaign.Audience(AudienceKind.FILTER, f.getId(), f.getName(), f.getFilter(), null, null, null,
                null, null, null, FilterKind.orSimple(f.getKind()), f.getQueryJson(), f.getUses());
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
                name.isEmpty() ? null : name, null, null, null, null);
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
