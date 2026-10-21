package me.nageshk.omnichannel.template;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.MustacheException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.api.ForbiddenException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.contact.ContactService;
import me.nageshk.omnichannel.template.TemplateView.PlaceholderView;
import org.springframework.stereotype.Service;

/**
 * Fills a template's placeholders from a contact and the agent's values. The context is built first and only the
 * template's own stored text is compiled, so contact and agent text is inserted as data and never interpreted.
 */
@Service
public class TemplateRenderer {

    public static final int MAX_AGENT_VALUE = 1000;

    /** Missing variables throw; text is not HTML-escaped; a variable is never looked up in another context. */
    static final Mustache.Compiler COMPILER = Mustache.compiler().escapeHTML(false).strictSections(true);

    private static final String EMPTY = "is empty after filling placeholders";
    private static final String OVERFLOW = "is longer than %d characters after filling placeholders";

    private final TemplateService templates;
    private final ContactService contacts;
    private final AccessService access;

    public TemplateRenderer(TemplateService templates, ContactService contacts, AccessService access) {
        this.templates = templates;
        this.contacts = contacts;
        this.access = access;
    }

    /**
     * Renders the template. A contact needs {@code contact:view} (403) and must belong to the brand (404); without
     * one every contact placeholder uses its fallback.
     */
    public RenderedTemplate render(AuthenticatedUser me, String workspaceId, String templateId, RenderRequest r) {
        MessageTemplate t = templates.require(workspaceId, templateId);
        rejectReservedNames(t);
        String contactId = r == null || r.contactId() == null || r.contactId().isBlank() ? null : r.contactId().trim();
        Contact contact = null;
        if (contactId != null) {
            if (!access.has(workspaceId, "contact:view")) throw new ForbiddenException("contact:view is required");
            contact = contacts.require(workspaceId, contactId);
        }
        TemplateView view = templates.view(t);
        Map<String, String> given = r == null || r.values() == null ? Map.of() : r.values();

        List<String> missing = new ArrayList<>();
        return filled(t, view, context(t, view, contact, given, missing), missing);
    }

    /**
     * Renders the template for a campaign, which has no user and no contact: every placeholder, whatever its kind,
     * takes its value from {@code values} under its name, stripped and cut to {@link #MAX_AGENT_VALUE}, and a blank
     * one its fallback. Nothing is ever missing. Checks no authority, so it runs without a signed-in user.
     *
     * @throws ValidationException if a filled field is empty or too long
     */
    public RenderedTemplate renderForCampaign(String workspaceId, String templateId, Map<String, String> values) {
        MessageTemplate t = templates.require(workspaceId, templateId);
        rejectReservedNames(t);
        Map<String, String> given = values == null ? Map.of() : values;
        return filled(t, templates.view(t), campaignContext(t, given), List.of());
    }

    /** Fills the template's text fields from the context; errors are collected and thrown together. */
    private static RenderedTemplate filled(MessageTemplate t, TemplateView view, Map<String, Object> context,
            List<String> missing) {
        TemplateContent in = t.getContent();
        TemplateContent out = copy(in);
        Map<String, String> errors = new LinkedHashMap<>();
        switch (t.getType()) {
            case TEXT -> out.setBody(fillRequired(in.getBody(), context, "content.body",
                    TemplateValidator.MAX_BODY, errors));
            case IMAGE, VIDEO, DOCUMENT -> out.setCaption(
                    fill(in.getCaption(), context, "content.caption", TemplateValidator.MAX_CAPTION, errors));
            case LOCATION -> {
                out.setName(fill(in.getName(), context, "content.name", TemplateValidator.MAX_LOCATION_TEXT, errors));
                out.setAddress(fill(in.getAddress(), context, "content.address",
                        TemplateValidator.MAX_LOCATION_TEXT, errors));
            }
            case CONTACT_CARD -> {
                out.setFullName(fillRequired(in.getFullName(), context, "content.fullName",
                        TemplateValidator.MAX_CARD_TEXT, errors));
                out.setOrganization(fill(in.getOrganization(), context, "content.organization",
                        TemplateValidator.MAX_CARD_TEXT, errors));
            }
            case POLL -> {
                out.setQuestion(fillRequired(in.getQuestion(), context, "content.question",
                        TemplateValidator.MAX_QUESTION, errors));
                out.setOptions(options(in.getOptions(), context, errors));
            }
            case AUDIO -> { }
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);

        String vcard = t.getType() == TemplateType.CONTACT_CARD ? vcard(out) : null;
        return new RenderedTemplate(t.getType(), out, view.media(), vcard, missing, view.warnings());
    }

    /**
     * Defence in depth: JMustache resolves {@code this} to the whole context, so a stored template that uses it (the
     * parser no longer lets one be saved) must never be compiled.
     */
    private static void rejectReservedNames(MessageTemplate t) {
        for (TemplatePlaceholder p : t.getPlaceholders()) {
            boolean reserved = p.getName() != null && List.of(p.getName().split("\\.", -1)).contains(PlaceholderParser.RESERVED)
                    || PlaceholderParser.RESERVED.equals(p.getAttributeKey());
            if (reserved) throw new IllegalStateException("template uses the reserved name \"this\"");
        }
    }

    /** Nested maps so that {@code contact.attributes.city} resolves as a compound key; agent names sit at the top. */
    private Map<String, Object> context(MessageTemplate t, TemplateView view, Contact contact,
            Map<String, String> given, List<String> missing) {
        Map<String, Object> top = new LinkedHashMap<>();
        Map<String, Object> contactMap = new LinkedHashMap<>();
        Map<String, Object> attributeMap = new LinkedHashMap<>();
        top.put("contact", contactMap);
        contactMap.put("attributes", attributeMap);
        for (int i = 0; i < t.getPlaceholders().size(); i++) {
            TemplatePlaceholder p = t.getPlaceholders().get(i);
            PlaceholderView pv = view.placeholders().get(i);
            String fallback = p.getFallback() == null ? "" : p.getFallback();
            switch (p.getKind()) {
                case CONTACT_FIELD -> {
                    String value = null;
                    if (contact != null) {
                        value = p.getName().equals("contact.phone")
                                ? (blank(contact.getPhone()) ? null : "+" + contact.getPhone().strip())
                                : contact.getName();
                    }
                    contactMap.put(p.getName().substring("contact.".length()), orFallback(value, fallback));
                }
                case CONTACT_ATTRIBUTE -> {
                    String value = contact == null || pv.missing() || p.getAttributeId() == null ? null
                            : contact.getAttributes().get(p.getAttributeId());
                    attributeMap.put(p.getAttributeKey(), orFallback(value, fallback));
                }
                case AGENT -> {
                    String value = givenValue(given, p);
                    if (value.isEmpty() && p.isRequired()) {
                        missing.add(p.getName());
                        value = "[" + p.getLabel() + "]";
                    } else if (value.isEmpty()) {
                        value = fallback;
                    }
                    top.put(p.getName(), value);
                }
            }
        }
        return top;
    }

    /** Like {@link #context} with every value taken from {@code given}, falling back when blank. */
    private static Map<String, Object> campaignContext(MessageTemplate t, Map<String, String> given) {
        Map<String, Object> top = new LinkedHashMap<>();
        Map<String, Object> contactMap = new LinkedHashMap<>();
        Map<String, Object> attributeMap = new LinkedHashMap<>();
        top.put("contact", contactMap);
        contactMap.put("attributes", attributeMap);
        for (TemplatePlaceholder p : t.getPlaceholders()) {
            String value = givenValue(given, p);
            if (value.isEmpty()) value = p.getFallback() == null ? "" : p.getFallback();
            switch (p.getKind()) {
                case CONTACT_FIELD -> contactMap.put(p.getName().substring("contact.".length()), value);
                case CONTACT_ATTRIBUTE -> attributeMap.put(p.getAttributeKey(), value);
                case AGENT -> top.put(p.getName(), value);
            }
        }
        return top;
    }

    /** The value given for the placeholder, stripped and cut to {@link #MAX_AGENT_VALUE}; never null. */
    private static String givenValue(Map<String, String> given, TemplatePlaceholder p) {
        String value = given.get(p.getName());
        value = value == null ? "" : value.strip();
        return value.length() > MAX_AGENT_VALUE ? value.substring(0, MAX_AGENT_VALUE) : value;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String orFallback(String value, String fallback) {
        return blank(value) ? fallback : value.strip();
    }

    /** The text with placeholders filled; null stays null. Overflow is recorded under {@code field}. */
    private static String fill(String text, Map<String, Object> context, String field, int max,
            Map<String, String> errors) {
        String out = execute(text, context);
        if (out != null && out.length() > max) errors.put(field, OVERFLOW.formatted(max));
        return out;
    }

    /** Like {@link #fill} for a field whose minimum length is 1: a blank result is recorded under {@code field}. */
    private static String fillRequired(String text, Map<String, Object> context, String field, int max,
            Map<String, String> errors) {
        String out = fill(text, context, field, max, errors);
        if (out != null && out.isBlank()) errors.put(field, EMPTY);
        return out;
    }

    private static String execute(String text, Map<String, Object> context) {
        if (text == null) return null;
        try {
            return COMPILER.compile(text).execute(context);
        } catch (MustacheException e) {
            // Every placeholder is in the context, so this means a template was stored that the parser would reject.
            throw new IllegalStateException("template text could not be rendered: " + e.getMessage(), e);
        }
    }

    private static List<String> options(List<String> in, Map<String, Object> context, Map<String, String> errors) {
        if (in == null) return null;
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String o : in) {
            String filled = execute(o, context);
            if (filled.isBlank()) {
                errors.put("content.options", EMPTY);
            } else if (filled.length() > TemplateValidator.MAX_OPTION) {
                errors.put("content.options", OVERFLOW.formatted(TemplateValidator.MAX_OPTION));
            } else if (!seen.add(filled.toLowerCase())) {
                errors.putIfAbsent("content.options", "options must be distinct, ignoring case, after filling placeholders");
            }
            out.add(filled);
        }
        return out;
    }

    /** A vCard 3.0 for the rendered card; the phone is the template's own digits. */
    private static String vcard(TemplateContent c) {
        List<String> lines = new ArrayList<>();
        lines.add("BEGIN:VCARD");
        lines.add("VERSION:3.0");
        lines.add("FN:" + escape(c.getFullName()));
        if (!blank(c.getOrganization())) lines.add("ORG:" + escape(c.getOrganization()));
        lines.add("TEL;type=CELL;waid=" + c.getPhone() + ":+" + c.getPhone());
        if (!blank(c.getEmail())) lines.add("EMAIL:" + escape(c.getEmail()));
        lines.add("END:VCARD");
        return String.join("\r\n", lines) + "\r\n";
    }

    /** Escapes a vCard text value: backslash, comma, semicolon and line breaks. */
    static String escape(String s) {
        String bs = "\\";
        String n = bs + "n";
        return s.replace(bs, bs + bs).replace(",", bs + ",").replace(";", bs + ";")
                .replace("\r\n", n).replace("\n", n).replace("\r", n);
    }

    private static TemplateContent copy(TemplateContent c) {
        TemplateContent o = new TemplateContent();
        o.setBody(c.getBody());
        o.setLinkPreview(c.getLinkPreview());
        o.setCaption(c.getCaption());
        o.setVoiceNote(c.getVoiceNote());
        o.setLatitude(c.getLatitude());
        o.setLongitude(c.getLongitude());
        o.setName(c.getName());
        o.setAddress(c.getAddress());
        o.setFullName(c.getFullName());
        o.setPhone(c.getPhone());
        o.setOrganization(c.getOrganization());
        o.setEmail(c.getEmail());
        o.setQuestion(c.getQuestion());
        o.setOptions(c.getOptions() == null ? null : new ArrayList<>(c.getOptions()));
        o.setAllowMultiple(c.getAllowMultiple());
        return o;
    }
}
