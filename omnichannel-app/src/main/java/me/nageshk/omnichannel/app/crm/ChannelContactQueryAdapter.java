package me.nageshk.omnichannel.app.crm;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.crm.platform.ChannelAccount;
import me.nageshk.omnichannel.crm.platform.ChannelAttribute;
import me.nageshk.omnichannel.crm.platform.ChannelContactQuery;
import me.nageshk.omnichannel.crm.platform.ChannelKind;
import me.nageshk.omnichannel.crm.platform.ChannelLabel;
import me.nageshk.omnichannel.crm.platform.ChannelRule;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.contact.ContactLabelRepository;
import me.nageshk.omnichannel.db.contact.advanced.Rule;
import me.nageshk.omnichannel.db.contact.advanced.RuleGroup;
import me.nageshk.omnichannel.db.phone.ConnectionType;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.service.contact.ContactQuery;
import me.nageshk.omnichannel.service.contact.advanced.AdvancedFilters;
import me.nageshk.omnichannel.service.contact.advanced.WorkspaceZone;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * The channel-contact side of CRM segments, answered by the host's own advanced-filter compiler: a segment rule on
 * labels, attributes, last message, source or do-not-contact becomes a one-rule filter tree, so the allowlist and
 * the query language stay the host's. Dates are day-granular in the brand's zone, as the host's filters are.
 */
@Component
class ChannelContactQueryAdapter implements ChannelContactQuery {

    private static final Duration MAX_TIME = Duration.ofSeconds(10);

    private final ContactLabelRepository labels;
    private final ContactAttributeRepository attributes;
    private final PhoneNumberRepository numbers;
    private final AdvancedFilters filters;
    private final ContactQuery contactQuery;
    private final WorkspaceZone zones;
    private final MongoTemplate mongo;

    ChannelContactQueryAdapter(ContactLabelRepository labels, ContactAttributeRepository attributes,
            PhoneNumberRepository numbers, AdvancedFilters filters, ContactQuery contactQuery, WorkspaceZone zones,
            MongoTemplate mongo) {
        this.labels = labels;
        this.attributes = attributes;
        this.numbers = numbers;
        this.filters = filters;
        this.contactQuery = contactQuery;
        this.zones = zones;
        this.mongo = mongo;
    }

    @Override
    public List<ChannelLabel> labels(String workspaceId) {
        return labels.findByWorkspaceIdOrderByNameKey(workspaceId).stream()
                .map(l -> new ChannelLabel(l.getId(), l.getName(), l.getColor())).toList();
    }

    @Override
    public List<ChannelAttribute> attributes(String workspaceId) {
        return attributes.findByWorkspaceIdOrderByNameKey(workspaceId).stream()
                .map(a -> new ChannelAttribute(a.getId(), a.getName())).toList();
    }

    @Override
    public List<ChannelAccount> accounts(String workspaceId) {
        return numbers.findByWorkspaceIdOrderByNumber(workspaceId).stream().map(ChannelContactQueryAdapter::account)
                .toList();
    }

    static ChannelAccount account(PhoneNumber n) {
        String label = n.getLabel() == null || n.getLabel().isBlank() ? n.getNumber() : n.getLabel();
        return new ChannelAccount(n.getId(), kindOf(n.getConnectionType()), label, n.getNumber());
    }

    /** A number without a recorded connection type predates the field and is a WhatsApp Web number. */
    static ChannelKind kindOf(ConnectionType type) {
        return type == ConnectionType.WHATSAPP_CLOUD_API ? ChannelKind.WHATSAPP_CLOUD : ChannelKind.WHATSAPP_WEB;
    }

    @Override
    public Set<String> matching(String workspaceId, ChannelRule rule, int limit) {
        RuleGroup tree = new RuleGroup("and", false, List.of(hostRule(rule, zones.zoneOf(workspaceId))));
        String json = filters.compile(workspaceId, tree).queryJson();
        Query q = new Query(contactQuery.advanced(workspaceId, json)).limit(limit + 1).maxTime(MAX_TIME);
        q.fields().include("_id");
        Set<String> ids = new HashSet<>();
        for (Contact c : mongo.find(q, Contact.class)) ids.add(c.getId());
        return ids;
    }

    /** The host's rule for a segment rule: same operators, host field names, instants folded to the brand's day. */
    static Rule hostRule(ChannelRule r, ZoneId zone) {
        String field = r.field();
        if ("label".equals(field)) return new Rule("labels", r.operator(), r.value());
        if (field.startsWith("attribute:")) {
            return new Rule("attributes." + field.substring("attribute:".length()), r.operator(), r.value());
        }
        if ("lastMessageAt".equals(field)) return new Rule("lastMessageAt", r.operator(), days(r.value(), zone));
        if ("source".equals(field) || "doNotContact".equals(field)) return new Rule(field, r.operator(), r.value());
        throw new IllegalArgumentException("not a channel-contact field: " + field);
    }

    private static Object days(Object value, ZoneId zone) {
        if (value instanceof List<?> l) return l.stream().map(v -> days(v, zone)).toList();
        if (value instanceof String s && !s.isBlank()) {
            return LocalDate.ofInstant(Instant.parse(s), zone).toString();
        }
        return value;
    }
}
