package me.nageshk.omnichannel.contact;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.api.DuplicateException;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.ValidationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** A brand's contact labels and attributes: validation, uniqueness, and cascade on delete. */
@Service
public class ContactFieldService {

    public static final Set<String> COLORS =
            Set.of("blue", "teal", "green", "orange", "purple", "red", "orangered", "yellow", "grey");

    private final ContactLabelRepository labels;
    private final ContactAttributeRepository attributes;
    private final MongoTemplate mongo;

    public ContactFieldService(ContactLabelRepository labels, ContactAttributeRepository attributes,
            MongoTemplate mongo) {
        this.labels = labels;
        this.attributes = attributes;
        this.mongo = mongo;
    }

    public List<LabelView> labelViews(String workspaceId) {
        return labels.findByWorkspaceIdOrderByNameKey(workspaceId).stream().map(this::view).toList();
    }

    public List<AttributeView> attributeViews(String workspaceId) {
        return attributes.findByWorkspaceIdOrderByNameKey(workspaceId).stream().map(this::view).toList();
    }

    public LabelView createLabel(String workspaceId, String name, String color, String description) {
        ContactLabel l = new ContactLabel();
        l.setWorkspaceId(workspaceId);
        return view(saveLabel(l, name, color, description));
    }

    public LabelView updateLabel(String workspaceId, String id, String name, String color, String description) {
        return view(saveLabel(requireLabel(workspaceId, id), name, color, description));
    }

    public void deleteLabel(String workspaceId, String id) {
        requireLabel(workspaceId, id);
        labels.deleteById(id);
        mongo.updateMulti(new Query(Criteria.where("workspaceId").is(workspaceId).and("labels").is(id)),
                new Update().pull("labels", id), Contact.class);
    }

    public AttributeView createAttribute(String workspaceId, String name, String description) {
        ContactAttribute a = new ContactAttribute();
        a.setWorkspaceId(workspaceId);
        return view(saveAttribute(a, name, description));
    }

    public AttributeView updateAttribute(String workspaceId, String id, String name, String description) {
        return view(saveAttribute(requireAttribute(workspaceId, id), name, description));
    }

    public void deleteAttribute(String workspaceId, String id) {
        requireAttribute(workspaceId, id);
        attributes.deleteById(id);
        String key = "attributes." + id;
        mongo.updateMulti(new Query(Criteria.where("workspaceId").is(workspaceId).and(key).exists(true)),
                new Update().unset(key), Contact.class);
    }

    public ContactLabel requireLabel(String workspaceId, String id) {
        return labels.findByIdAndWorkspaceId(id, workspaceId).orElseThrow(() -> new NotFoundException("label not found"));
    }

    public ContactAttribute requireAttribute(String workspaceId, String id) {
        return attributes.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new NotFoundException("attribute not found"));
    }

    private ContactLabel saveLabel(ContactLabel l, String name, String color, String description) {
        Map<String, String> errors = new LinkedHashMap<>();
        String n = name(name, errors);
        if (color == null || !COLORS.contains(color)) errors.put("color", "must be one of " + String.join(", ", COLORS));
        String d = description(description, errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        ContactLabel clash = labels.findByWorkspaceIdAndNameKey(l.getWorkspaceId(), n.toLowerCase()).orElse(null);
        if (clash != null && !clash.getId().equals(l.getId())) throw duplicate(n);
        l.setName(n);
        l.setColor(color);
        l.setDescription(d);
        try {
            return labels.save(l);
        } catch (DuplicateKeyException e) {
            throw duplicate(n);
        }
    }

    private ContactAttribute saveAttribute(ContactAttribute a, String name, String description) {
        Map<String, String> errors = new LinkedHashMap<>();
        String n = name(name, errors);
        String d = description(description, errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        ContactAttribute clash = attributes.findByWorkspaceIdAndNameKey(a.getWorkspaceId(), n.toLowerCase()).orElse(null);
        if (clash != null && !clash.getId().equals(a.getId())) throw duplicate(n);
        a.setName(n);
        a.setDescription(d);
        if (a.getKey() == null) a.setKey(AttributeKeys.generate(n, takenKeys(a.getWorkspaceId())));
        try {
            return attributes.save(a);
        } catch (DuplicateKeyException e) {
            throw duplicate(n);
        }
    }

    private Set<String> takenKeys(String workspaceId) {
        Set<String> taken = new HashSet<>();
        attributes.findByWorkspaceIdOrderByNameKey(workspaceId).forEach(x -> {
            if (x.getKey() != null) taken.add(x.getKey());
        });
        return taken;
    }

    private static DuplicateException duplicate(String name) {
        return new DuplicateException("name", "\"" + name + "\" already exists in this brand");
    }

    private static String name(String raw, Map<String, String> errors) {
        String n = raw == null ? "" : raw.trim();
        if (n.isEmpty() || n.length() > 40) errors.put("name", "must be 1 to 40 characters");
        return n;
    }

    private static String description(String raw, Map<String, String> errors) {
        String d = raw == null ? "" : raw.trim();
        if (d.length() > 200) errors.put("description", "must be at most 200 characters");
        return d.isEmpty() ? null : d;
    }

    private LabelView view(ContactLabel l) {
        long n = mongo.count(new Query(Criteria.where("workspaceId").is(l.getWorkspaceId()).and("labels").is(l.getId())),
                Contact.class);
        return new LabelView(l.getId(), l.getName(), l.getColor(), l.getDescription(), n);
    }

    private AttributeView view(ContactAttribute a) {
        long n = mongo.count(new Query(Criteria.where("workspaceId").is(a.getWorkspaceId())
                .and("attributes." + a.getId()).exists(true)), Contact.class);
        return new AttributeView(a.getId(), a.getKey(), a.getName(), a.getDescription(), n);
    }
}
