package me.nageshk.omnichannel.template;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.api.DuplicateException;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.TemplateInUseException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.phone.ConnectionType;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.template.TemplateRequest.PlaceholderSetting;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** A brand's message templates: validation, uniqueness, placeholder settings and lifecycle. */
@Service
public class TemplateService {

    public static final int MAX_NAME = 100;
    public static final int MAX_CATEGORY = 40;
    public static final int MAX_LABEL = 60;
    public static final int MAX_SETTING_TEXT = 200;

    private final MessageTemplateRepository templates;
    private final TemplateValidator validator;
    private final MongoTemplate mongo;
    private final UserRepository users;
    private final WorkspaceRepository workspaces;

    public TemplateService(MessageTemplateRepository templates, TemplateValidator validator, MongoTemplate mongo,
            UserRepository users, WorkspaceRepository workspaces) {
        this.templates = templates;
        this.validator = validator;
        this.mongo = mongo;
        this.users = users;
        this.workspaces = workspaces;
    }

    /** Matching templates sorted by name; {@code status} defaults to ACTIVE, the other filters are optional. */
    public List<TemplateView> list(String workspaceId, String q, TemplateType type, String category,
            TemplateStatus status) {
        Criteria c = Criteria.where("workspaceId").is(workspaceId).and("status")
                .is(status == null ? TemplateStatus.ACTIVE : status);
        if (type != null) c.and("type").is(type);
        if (category != null && !category.isBlank()) c.and("category").is(category.trim());
        if (q != null && !q.isBlank()) c.and("name").regex(Pattern.quote(q.trim()), "i");
        return mongo.find(new Query(c).with(Sort.by("nameKey")), MessageTemplate.class).stream()
                .map(TemplateView::of).toList();
    }

    /** The brand's distinct categories, sorted ignoring case. */
    public List<String> categories(String workspaceId) {
        return mongo.findDistinct(new Query(Criteria.where("workspaceId").is(workspaceId)), "category",
                MessageTemplate.class, String.class).stream()
                .filter(s -> s != null && !s.isBlank())
                .sorted(Comparator.comparing((String s) -> s.toLowerCase()).thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    public TemplateView get(String workspaceId, String id) {
        return TemplateView.of(require(workspaceId, id));
    }

    public MessageTemplate require(String workspaceId, String id) {
        return templates.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new NotFoundException("template not found"));
    }

    public TemplateView create(AuthenticatedUser me, String workspaceId, TemplateRequest r) {
        MessageTemplate t = new MessageTemplate();
        t.setWorkspaceId(workspaceId);
        t.setCustomerId(workspaces.findById(workspaceId).map(Workspace::getCustomerId).orElse(me.customerId()));
        t.setChannel(ConnectionType.WHATSAPP_WEB);
        t.setStatus(TemplateStatus.ACTIVE);
        String[] who = who(me);
        t.setCreatedByUsername(who[0]);
        t.setCreatedByName(who[1]);
        return TemplateView.of(save(t, who, r, r == null ? null : r.type(), false));
    }

    /** Replaces name, category, content and placeholders; {@code type} is ignored and {@code version} is required. */
    public TemplateView update(AuthenticatedUser me, String workspaceId, String id, TemplateRequest r) {
        MessageTemplate t = require(workspaceId, id);
        return TemplateView.of(save(t, who(me), r, t.getType(), true));
    }

    public TemplateView archive(AuthenticatedUser me, String workspaceId, String id) {
        return setStatus(me, workspaceId, id, TemplateStatus.ARCHIVED);
    }

    public TemplateView unarchive(AuthenticatedUser me, String workspaceId, String id) {
        return setStatus(me, workspaceId, id, TemplateStatus.ACTIVE);
    }

    /** Deletes an unused template; a used one can only be archived. */
    public void delete(String workspaceId, String id) {
        MessageTemplate t = require(workspaceId, id);
        if (t.getLastUsedAt() != null) throw new TemplateInUseException();
        templates.delete(t);
    }

    private TemplateView setStatus(AuthenticatedUser me, String workspaceId, String id, TemplateStatus status) {
        MessageTemplate t = require(workspaceId, id);
        t.setStatus(status);
        String[] who = who(me);
        t.setUpdatedByUsername(who[0]);
        t.setUpdatedByName(who[1]);
        return TemplateView.of(templates.save(t));
    }

    private MessageTemplate save(MessageTemplate t, String[] who, TemplateRequest r, TemplateType type,
            boolean update) {
        if (r == null) throw new ValidationException("body", "is required");
        Map<String, String> errors = new LinkedHashMap<>();

        String name = r.name() == null ? "" : r.name().trim();
        if (name.isEmpty() || name.length() > MAX_NAME) errors.put("name", "must be 1 to " + MAX_NAME + " characters");
        String category = r.category() == null || r.category().isBlank() ? null : r.category().trim();
        if (category != null && category.length() > MAX_CATEGORY) {
            errors.put("category", "must be at most " + MAX_CATEGORY + " characters");
        }
        if (update && r.version() == null) errors.put("version", "is required");
        if (r.mediaId() != null && !r.mediaId().isBlank()) errors.put("mediaId", "is not allowed for this type");

        TemplateContent content = null;
        List<PlaceholderRef> refs = List.of();
        if (type == null) {
            errors.put("type", "is required");
        } else {
            content = r.content();
            refs = validator.validate(type, content, errors);
        }
        List<TemplatePlaceholder> placeholders = placeholders(refs, r.placeholders(), errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);

        String nameKey = name.toLowerCase();
        templates.findByWorkspaceIdAndNameKey(t.getWorkspaceId(), nameKey).filter(o -> !o.getId().equals(t.getId()))
                .ifPresent(o -> { throw clash(name); });

        t.setName(name);
        t.setNameKey(nameKey);
        t.setCategory(category);
        t.setType(type);
        t.setContent(validator.clean(type, content));
        t.setPlaceholders(placeholders);
        t.setUpdatedByUsername(who[0]);
        t.setUpdatedByName(who[1]);
        if (update) t.setVersion(r.version());
        try {
            return templates.save(t);
        } catch (DuplicateKeyException e) {
            throw clash(name);
        }
    }

    /** One placeholder per detected ref, in order; settings for placeholders not in the content are dropped. */
    private static List<TemplatePlaceholder> placeholders(List<PlaceholderRef> refs, List<PlaceholderSetting> given,
            Map<String, String> errors) {
        Map<String, PlaceholderSetting> byName = new LinkedHashMap<>();
        if (given != null) {
            for (PlaceholderSetting s : given) if (s != null && s.name() != null) byName.putIfAbsent(s.name().trim(), s);
        }
        List<TemplatePlaceholder> out = new ArrayList<>();
        for (PlaceholderRef ref : refs) {
            String field = "placeholders." + ref.name();
            if (ref.kind() == PlaceholderKind.CONTACT_ATTRIBUTE) {
                errors.put(field, "not supported yet");
                continue;
            }
            PlaceholderSetting s = byName.get(ref.name());
            if (s == null) {
                errors.put(field, "settings are missing");
                continue;
            }
            TemplatePlaceholder p = new TemplatePlaceholder();
            p.setName(ref.name());
            p.setKind(ref.kind());
            String fallback = s.fallback() == null ? null : s.fallback().strip();
            if (ref.kind() == PlaceholderKind.CONTACT_FIELD) {
                if (fallback == null || fallback.isEmpty()) {
                    errors.put(field + ".fallback", "is required");
                } else if (fallback.length() > MAX_SETTING_TEXT) {
                    errors.put(field + ".fallback", "must be at most " + MAX_SETTING_TEXT + " characters");
                }
                p.setFallback(fallback);
            } else {
                boolean required = Boolean.TRUE.equals(s.required());
                String label = s.label() == null ? "" : s.label().strip();
                String example = s.example() == null || s.example().isBlank() ? null : s.example().strip();
                if (label.isEmpty() || label.length() > MAX_LABEL) {
                    errors.put(field + ".label", "must be 1 to " + MAX_LABEL + " characters");
                }
                if (example != null && example.length() > MAX_SETTING_TEXT) {
                    errors.put(field + ".example", "must be at most " + MAX_SETTING_TEXT + " characters");
                }
                if (fallback != null && fallback.isEmpty()) fallback = null;
                if (fallback != null && required) {
                    errors.put(field + ".fallback", "is not allowed on a required field");
                } else if (fallback != null && fallback.length() > MAX_SETTING_TEXT) {
                    errors.put(field + ".fallback", "must be at most " + MAX_SETTING_TEXT + " characters");
                }
                p.setLabel(label);
                p.setExample(example);
                p.setRequired(required);
                p.setFallback(fallback);
            }
            out.add(p);
        }
        return out;
    }

    private static DuplicateException clash(String name) {
        return new DuplicateException("name", "\"" + name + "\" already exists in this brand");
    }

    /** Username and display name of the caller, as stored on the template. */
    private String[] who(AuthenticatedUser me) {
        return new String[] {me.getUsername(),
                users.findById(me.userId()).map(User::getName).orElse(me.getUsername())};
    }
}
