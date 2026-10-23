package me.nageshk.omnichannel.service.template;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.common.api.DuplicateException;
import me.nageshk.omnichannel.common.api.ForbiddenException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.TemplateInUseException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.contact.ContactAttribute;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.phone.ConnectionType;
import me.nageshk.omnichannel.db.template.MessageTemplate;
import me.nageshk.omnichannel.db.template.MessageTemplateRepository;
import me.nageshk.omnichannel.db.template.PlaceholderKind;
import me.nageshk.omnichannel.db.template.TemplateContent;
import me.nageshk.omnichannel.db.template.TemplateMediaRef;
import me.nageshk.omnichannel.db.template.TemplatePlaceholder;
import me.nageshk.omnichannel.db.template.TemplateStatus;
import me.nageshk.omnichannel.db.template.TemplateType;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.template.TemplateRequest.PlaceholderSetting;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
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
    private final ContactAttributeRepository attributes;
    private final TemplateMediaService media;
    private final AccessService access;

    public TemplateService(MessageTemplateRepository templates, TemplateValidator validator, MongoTemplate mongo,
            UserRepository users, WorkspaceRepository workspaces, ContactAttributeRepository attributes,
            TemplateMediaService media, AccessService access) {
        this.access = access;
        this.attributes = attributes;
        this.media = media;
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
        return views(workspaceId, mongo.find(new Query(c).with(Sort.by("nameKey")), MessageTemplate.class));
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
        return view(require(workspaceId, id));
    }

    /** The view of one template with warnings computed against the brand's current attributes. */
    public TemplateView view(MessageTemplate t) {
        return views(t.getWorkspaceId(), List.of(t)).get(0);
    }

    /** Views of several templates of one brand; the brand's attributes are loaded once, and only if needed. */
    private List<TemplateView> views(String workspaceId, List<MessageTemplate> found) {
        boolean needed = found.stream().flatMap(t -> t.getPlaceholders().stream())
                .anyMatch(p -> p.getKind() == PlaceholderKind.CONTACT_ATTRIBUTE);
        List<ContactAttribute> current = needed ? attributes.findByWorkspaceIdOrderByNameKey(workspaceId) : List.of();
        return found.stream().map(t -> TemplateView.of(t, current)).toList();
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
        return view(save(t, who, r, r == null ? null : r.type(), false));
    }

    /** Replaces name, category, content and placeholders; {@code type} is ignored and {@code version} is required. */
    public TemplateView update(AuthenticatedUser me, String workspaceId, String id, TemplateRequest r) {
        MessageTemplate t = require(workspaceId, id);
        return view(save(t, who(me), r, t.getType(), true));
    }

    /**
     * Copies a template into the target brand (possibly the same one) as a new ACTIVE template with the given name.
     * The target must be a brand of the caller's customer (else 404) where the caller can manage templates (else 403).
     * Attribute placeholders are bound by key in the target; a key the target lacks stays with no attribute and reads
     * as missing. The media file is copied, not shared.
     */
    public TemplateView duplicate(AuthenticatedUser me, String workspaceId, String id, String targetWorkspaceId,
            String newName) {
        MessageTemplate src = require(workspaceId, id);
        Map<String, String> errors = new LinkedHashMap<>();
        if (targetWorkspaceId == null || targetWorkspaceId.isBlank()) errors.put("targetWorkspaceId", "is required");
        String name = newName == null ? "" : newName.trim();
        if (name.isEmpty() || name.length() > MAX_NAME) errors.put("name", "must be 1 to " + MAX_NAME + " characters");
        if (!errors.isEmpty()) throw new ValidationException(errors);

        String target = targetWorkspaceId.trim();
        Workspace brand = me.customerId() == null ? null
                : workspaces.findByIdAndCustomerId(target, me.customerId()).orElse(null);
        if (brand == null) throw new NotFoundException("workspace not found");
        if (!access.has(target, "template:manage")) throw new ForbiddenException("template:manage is required");

        String nameKey = name.toLowerCase();
        if (templates.findByWorkspaceIdAndNameKey(target, nameKey).isPresent()) throw clash(name);

        String[] who = who(me);
        MessageTemplate copy = new MessageTemplate();
        copy.setWorkspaceId(target);
        copy.setCustomerId(brand.getCustomerId());
        copy.setChannel(src.getChannel());
        copy.setName(name);
        copy.setNameKey(nameKey);
        copy.setCategory(src.getCategory());
        copy.setType(src.getType());
        copy.setContent(src.getContent());
        copy.setStatus(TemplateStatus.ACTIVE);
        copy.setCreatedByUsername(who[0]);
        copy.setCreatedByName(who[1]);
        copy.setUpdatedByUsername(who[0]);
        copy.setUpdatedByName(who[1]);
        copy.setPlaceholders(src.getPlaceholders().stream().map(p -> rebound(target, p)).toList());
        String copiedMediaId = null;
        if (src.getMedia() != null) {
            copiedMediaId = media.copy(workspaceId, src.getMedia().getMediaId(), target);
            TemplateMediaRef m = new TemplateMediaRef();
            m.setMediaId(copiedMediaId);
            m.setFileName(src.getMedia().getFileName());
            m.setMimeType(src.getMedia().getMimeType());
            m.setSize(src.getMedia().getSize());
            copy.setMedia(m);
        }
        try {
            return view(templates.save(copy));
        } catch (DuplicateKeyException e) {
            if (copiedMediaId != null) media.delete(copiedMediaId);
            throw clash(name);
        } catch (RuntimeException e) {
            if (copiedMediaId != null) media.delete(copiedMediaId);
            throw e;
        }
    }

    /** A copy of the placeholder for the target brand: an attribute placeholder is bound to the target's key or to none. */
    private TemplatePlaceholder rebound(String targetWorkspaceId, TemplatePlaceholder p) {
        TemplatePlaceholder c = new TemplatePlaceholder();
        c.setName(p.getName());
        c.setKind(p.getKind());
        c.setAttributeKey(p.getAttributeKey());
        c.setLabel(p.getLabel());
        c.setExample(p.getExample());
        c.setRequired(p.isRequired());
        c.setFallback(p.getFallback());
        if (p.getKind() == PlaceholderKind.CONTACT_ATTRIBUTE) {
            c.setAttributeId(attributes.findByWorkspaceIdAndKey(targetWorkspaceId, p.getAttributeKey())
                    .map(ContactAttribute::getId).orElse(null));
        }
        return c;
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
        if (t.getMedia() != null) media.delete(t.getMedia().getMediaId());
    }

    /**
     * Records that a send used the template. A targeted update by collection name, which Spring Data does not
     * treat as a versioned write: the template's version and edit time stay as they are, so an author's pending
     * save is not rejected.
     */
    public void markUsed(String templateId, Instant at) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(templateId)), new Update().set("lastUsedAt", at),
                mongo.getCollectionName(MessageTemplate.class));
    }

    private TemplateView setStatus(AuthenticatedUser me, String workspaceId, String id, TemplateStatus status) {
        MessageTemplate t = require(workspaceId, id);
        t.setStatus(status);
        String[] who = who(me);
        t.setUpdatedByUsername(who[0]);
        t.setUpdatedByName(who[1]);
        return view(templates.save(t));
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

        TemplateContent content = null;
        List<PlaceholderRef> refs = List.of();
        if (type == null) {
            errors.put("type", "is required");
        } else {
            content = r.content();
            refs = validator.validate(type, content, errors);
        }
        TemplateMediaService.Meta file = media(t, type, r, content, errors);
        List<TemplatePlaceholder> placeholders = placeholders(t.getWorkspaceId(), refs, r.placeholders(),
                update ? t.getPlaceholders() : List.of(), errors);
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
        String oldMediaId = t.getMedia() == null ? null : t.getMedia().getMediaId();
        t.setMedia(file == null ? null : ref(file.view()));
        if (update) t.setVersion(r.version());
        MessageTemplate saved;
        try {
            saved = templates.save(t);
        } catch (DuplicateKeyException e) {
            throw clash(name);
        }
        if (oldMediaId != null && (file == null || !oldMediaId.equals(file.view().mediaId()))) media.delete(oldMediaId);
        return saved;
    }

    /**
     * Checks the request's file against the type: media types need an uploaded file of this brand with an allowed MIME
     * type that no other template uses; other types take none. Returns the file's details, or null when there is none.
     */
    private TemplateMediaService.Meta media(MessageTemplate t, TemplateType type, TemplateRequest r,
            TemplateContent content, Map<String, String> errors) {
        String id = r.mediaId() == null || r.mediaId().isBlank() ? null : r.mediaId().trim();
        if (type == null) return null;
        if (!TemplateMediaTypes.takesMedia(type)) {
            if (id != null) errors.put("mediaId", "is not allowed for this type");
            return null;
        }
        if (id == null) {
            errors.put("mediaId", "is required");
            return null;
        }
        TemplateMediaService.Meta file = media.meta(t.getWorkspaceId(), id).orElse(null);
        if (file == null) {
            errors.put("mediaId", "not found");
            return null;
        }
        String mime = file.view().mimeType();
        if (!TemplateMediaTypes.allows(type, mime)) {
            errors.put("mediaId", TemplateMediaTypes.describe(mime) + " can't be used for "
                    + TemplateMediaTypes.describe(type) + " template");
        } else if (mongo.exists(new Query(Criteria.where("media.mediaId").is(id)
                .and("_id").ne(t.getId())), MessageTemplate.class)) {
            errors.put("mediaId", "is used by another template");
        } else if (type == TemplateType.AUDIO && content != null && Boolean.TRUE.equals(content.getVoiceNote())
                && !file.opus()) {
            errors.put("content.voiceNote", "is allowed only for Ogg/Opus audio");
        }
        return file;
    }

    private static TemplateMediaRef ref(MediaView v) {
        TemplateMediaRef m = new TemplateMediaRef();
        m.setMediaId(v.mediaId());
        m.setFileName(v.fileName());
        m.setMimeType(v.mimeType());
        m.setSize(v.size());
        return m;
    }

    /** One placeholder per detected ref, in order; settings for placeholders not in the content are dropped. */
    private List<TemplatePlaceholder> placeholders(String workspaceId, List<PlaceholderRef> refs,
            List<PlaceholderSetting> given, List<TemplatePlaceholder> previous, Map<String, String> errors) {
        Map<String, PlaceholderSetting> byName = new LinkedHashMap<>();
        if (given != null) {
            for (PlaceholderSetting s : given) if (s != null && s.name() != null) byName.putIfAbsent(s.name().trim(), s);
        }
        List<TemplatePlaceholder> out = new ArrayList<>();
        for (PlaceholderRef ref : refs) {
            String field = "placeholders." + ref.name();
            PlaceholderSetting s = byName.get(ref.name());
            if (s == null) {
                errors.put(field, "settings are missing");
                continue;
            }
            TemplatePlaceholder p = new TemplatePlaceholder();
            p.setName(ref.name());
            p.setKind(ref.kind());
            String fallback = s.fallback() == null ? null : s.fallback().strip();
            if (ref.kind() == PlaceholderKind.CONTACT_ATTRIBUTE) {
                p.setAttributeKey(ref.attributeKey());
                p.setAttributeId(bind(workspaceId, ref, previous));
                if (p.getAttributeId() == null && previous.stream().noneMatch(o -> o.getName().equals(ref.name())
                        && o.getKind() == PlaceholderKind.CONTACT_ATTRIBUTE)) {
                    errors.put(field, "Unknown contact attribute \"" + ref.attributeKey() + "\"");
                }
            }
            if (ref.kind() != PlaceholderKind.AGENT) {
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

    /** The id of the brand's attribute with the ref's key, else the id the previous version was bound to, else null. */
    private String bind(String workspaceId, PlaceholderRef ref, List<TemplatePlaceholder> previous) {
        return attributes.findByWorkspaceIdAndKey(workspaceId, ref.attributeKey()).map(ContactAttribute::getId)
                .orElseGet(() -> previous.stream().filter(o -> o.getName().equals(ref.name()))
                        .map(TemplatePlaceholder::getAttributeId).filter(java.util.Objects::nonNull).findFirst()
                        .orElse(null));
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
