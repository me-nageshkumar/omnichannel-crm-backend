package me.nageshk.omnichannel.service.contact.advanced;

import java.util.Set;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.db.contact.ContactAttribute;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.contact.ContactLabel;
import me.nageshk.omnichannel.db.contact.ContactLabelRepository;
import me.nageshk.omnichannel.db.contact.advanced.RuleGroup;
import me.nageshk.omnichannel.db.contact.advanced.Uses;
import org.bson.Document;
import org.springframework.stereotype.Service;

/** Validates and compiles rule trees against a workspace's labels, attributes and time zone. */
@Service
public class AdvancedFilters {

    public static final int QUERY_VERSION = 1;

    public record Compiled(String queryJson, Uses uses) {}

    private final ContactLabelRepository labels;
    private final ContactAttributeRepository attributes;
    private final WorkspaceZone zones;

    public AdvancedFilters(ContactLabelRepository labels, ContactAttributeRepository attributes, WorkspaceZone zones) {
        this.labels = labels;
        this.attributes = attributes;
        this.zones = zones;
    }

    public Resolved resolve(String workspaceId) {
        Set<String> attributeIds = attributes.findByWorkspaceIdOrderByNameKey(workspaceId).stream()
                .map(ContactAttribute::getId).collect(Collectors.toSet());
        Set<String> labelIds = labels.findByWorkspaceIdOrderByNameKey(workspaceId).stream()
                .map(ContactLabel::getId).collect(Collectors.toSet());
        return new Resolved(attributeIds, labelIds, zones.zoneOf(workspaceId));
    }

    /** Validates, then compiles; the stored query has no workspace clause. */
    public Compiled compile(String workspaceId, RuleGroup rules) {
        Resolved resolved = resolve(workspaceId);
        Uses uses = AdvancedFilterValidator.validate(rules, resolved);
        return new Compiled(AdvancedFilterCompiler.compile(rules, resolved).toJson(), uses);
    }

    /** Only for JSON written by {@link #compile}, never for client input. */
    public static Document parse(String queryJson) {
        return Document.parse(queryJson);
    }
}
