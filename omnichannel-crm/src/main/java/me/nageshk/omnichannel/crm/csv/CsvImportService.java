package me.nageshk.omnichannel.crm.csv;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.common.CrmErrors;
import me.nageshk.omnichannel.crm.common.PicklistItem;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.company.Address;
import me.nageshk.omnichannel.crm.company.CrmCompany;
import me.nageshk.omnichannel.crm.company.CrmCompanyService;
import me.nageshk.omnichannel.crm.company.dto.CompanyRequest;
import me.nageshk.omnichannel.crm.company.dto.CompanyView;
import me.nageshk.omnichannel.crm.contact.CaptureService;
import me.nageshk.omnichannel.crm.contact.CrmContactService;
import me.nageshk.omnichannel.crm.contact.Origin;
import me.nageshk.omnichannel.crm.contact.OriginKind;
import me.nageshk.omnichannel.crm.contact.dto.CaptureResult;
import me.nageshk.omnichannel.crm.contact.dto.ContactUpdateRequest;
import me.nageshk.omnichannel.crm.contact.dto.CrmContactView;
import me.nageshk.omnichannel.crm.csv.dto.ImportResult;
import me.nageshk.omnichannel.crm.field.CrmFieldDefinition;
import me.nageshk.omnichannel.crm.field.FieldDefinitionService;
import me.nageshk.omnichannel.crm.field.FieldType;
import me.nageshk.omnichannel.crm.platform.ContactDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformContact;
import me.nageshk.omnichannel.crm.platform.PlatformNumber;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Synchronous, bounded CSV import of companies and contacts (spec §6.7). Headers match case-insensitively; a custom
 * field column is {@code <label> (<key>)} or the bare key. Blank cells never overwrite, and a bad row is reported in
 * the result without stopping the file.
 */
@Service
public class CsvImportService {

    static final int MAX_COMPANIES = 2000;
    static final int MAX_CONTACTS = 1000;

    private static final Pattern KEYED = Pattern.compile("^.*\\(([a-z][a-zA-Z0-9_]{1,39})\\)$");

    private final MongoTemplate mongo;
    private final CrmCompanyService companies;
    private final CrmContactService contacts;
    private final CaptureService capture;
    private final ContactDirectory directory;
    private final FieldDefinitionService fields;

    CsvImportService(MongoTemplate mongo, CrmCompanyService companies, CrmContactService contacts,
            CaptureService capture, ContactDirectory directory, FieldDefinitionService fields) {
        this.mongo = mongo;
        this.companies = companies;
        this.contacts = contacts;
        this.capture = capture;
        this.directory = directory;
        this.fields = fields;
    }

    /** Upserts by case-insensitive name, then by domain; a matched company keeps its name. */
    public ImportResult importCompanies(CrmContext ctx, MultipartFile file) {
        ctx.require("crm:edit");
        Sheet sheet = read(file, "Name", MAX_COMPANIES);
        Map<String, Column> custom = customColumns(ctx, TargetType.COMPANY, sheet);
        Tally t = new Tally(sheet.rows.size());
        for (int i = 0; i < sheet.rows.size(); i++) {
            Row r = new Row(sheet, sheet.rows.get(i));
            try {
                companyRow(ctx, r, customValues(r, custom), t);
            } catch (ValidationException | CodedException | NotFoundException e) {
                t.error(i + 1, message(e));
            }
        }
        return t.result();
    }

    /**
     * Each row is a §6.1 create with origin {@code IMPORT} on the number into the pipeline: an existing CRM contact is
     * filled where blank and gets a deal only when it has none open in the pipeline.
     */
    public ImportResult importContacts(CrmContext ctx, MultipartFile file, String pipelineId, String phoneNumberId) {
        PlatformNumber channel = capture.importChannel(ctx, phoneNumberId);
        if (pipelineId == null || pipelineId.isBlank()) throw new ValidationException("pipelineId", "is required");
        String pipeline = capture.resolvePipeline(ctx, pipelineId);
        Sheet sheet = read(file, "WhatsApp number", MAX_CONTACTS);
        Map<String, Column> custom = customColumns(ctx, TargetType.CONTACT, sheet);
        Map<String, String> companyIds = new HashMap<>();
        Tally t = new Tally(sheet.rows.size());
        for (int i = 0; i < sheet.rows.size(); i++) {
            Row r = new Row(sheet, sheet.rows.get(i));
            try {
                contactRow(ctx, r, customValues(r, custom), channel, pipeline, companyIds, t);
            } catch (ValidationException | CodedException | NotFoundException e) {
                t.error(i + 1, message(e));
            }
        }
        return t.result();
    }

    // --- rows ----------------------------------------------------------------------------------------------

    private void companyRow(CrmContext ctx, Row r, Map<String, Object> custom, Tally t) {
        String name = r.get("Name");
        if (name == null) throw new ValidationException("Name", "is required");
        String domain = r.get("Domain");
        BigDecimal revenue = number(r.get("Annual revenue"), "Annual revenue");
        String industry = pick(r.get("Industry"), ctx.settings().getIndustries(), "Industry");
        String city = r.get("City");
        String country = r.get("Country");
        CrmCompany existing = byName(ctx, name);
        if (existing == null && domain != null) existing = byDomain(ctx, domain);
        Map<String, Object> customFields = custom.isEmpty() ? null : custom;
        if (existing == null) {
            Address address = city == null && country == null ? null : new Address(null, city, null, null, country);
            companies.create(ctx, new CompanyRequest(null, name, domain, r.get("Website"), industry, r.get("Size"),
                    revenue, r.get("Phone"), r.get("Email"), address, r.get("Description"), null, null, null,
                    customFields));
            t.created++;
            return;
        }
        Address a = existing.getAddress();
        Address address = city == null && country == null ? null : new Address(a == null ? null : a.line1(),
                city != null ? city : a == null ? null : a.city(), a == null ? null : a.state(),
                a == null ? null : a.postalCode(), country != null ? country : a == null ? null : a.country());
        CompanyView after = companies.update(ctx, existing.getId(), new CompanyRequest(existing.getVersion(), null,
                other(domain, existing.getDomain()), other(r.get("Website"), existing.getWebsite()), industry,
                other(r.get("Size"), existing.getSize()), revenue, other(r.get("Phone"), existing.getPhone()),
                other(r.get("Email"), existing.getEmail()), address, r.get("Description"), null, null, null,
                customFields));
        if (after.version().equals(existing.getVersion())) t.skipped++;
        else t.updated++;
    }

    private void contactRow(CrmContext ctx, Row r, Map<String, Object> custom, PlatformNumber channel,
            String pipelineId, Map<String, String> companyIds, Tally t) {
        String phone = r.get("WhatsApp number");
        if (phone == null) throw new ValidationException("WhatsApp number", "is required");
        String first = r.get("First name");
        String last = r.get("Last name");
        String lifecycle = pick(r.get("Lifecycle"), ctx.settings().getLifecycleStatuses(), "Lifecycle");
        String source = pick(r.get("Source"), ctx.settings().getDealSources(), "Source");
        String companyName = r.get("Company");
        String fullName = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        PlatformContact pc;
        try {
            pc = directory.ensure(ctx.workspaceId(), ctx.customerId(), phone, fullName.isEmpty() ? null : fullName);
        } catch (ValidationException e) {
            throw new ValidationException("WhatsApp number", e.fields().getOrDefault("phone", "is not valid"));
        }
        Origin origin = new Origin(OriginKind.IMPORT, channel.id(), null, null, ctx.userId());
        CaptureResult result = capture.findOrCreate(ctx, pc, channel, origin, pipelineId, null, first, last);
        CrmContactView v = result.contact();
        boolean created = result.createdContact();
        String companyId = companyName != null && (created || v.company() == null)
                ? companyIdFor(ctx, companyName, companyIds) : null;
        Map<String, Object> customFields = new LinkedHashMap<>();
        custom.forEach((k, value) -> {
            Object current = v.customFields() == null ? null : v.customFields().get(k);
            if (created || current == null || (current instanceof String s && s.isBlank())) customFields.put(k, value);
        });
        ContactUpdateRequest u = new ContactUpdateRequest(v.version(), fill(first, v.firstName(), created),
                fill(last, v.lastName(), created), fill(r.get("Email"), v.email(), created),
                fill(r.get("Job title"), v.jobTitle(), created), null, fill(lifecycle, v.lifecycleStatus(), created),
                fill(source, v.source(), created), companyId, customFields.isEmpty() ? null : customFields);
        boolean changes = u.firstName() != null || u.lastName() != null || u.email() != null || u.jobTitle() != null
                || u.lifecycleStatus() != null || u.source() != null || u.companyId() != null
                || u.customFields() != null;
        if (changes) contacts.update(ctx, v.id(), u);
        if (created) t.created++;
        else if (changes || result.createdDeal()) t.updated++;
        else t.skipped++;
    }

    /** The cell, or null (leave unchanged) when it differs from the current value only in case. */
    private static String other(String cell, String current) {
        return cell != null && cell.equalsIgnoreCase(current) ? null : cell;
    }

    /** The value when the record is new or its current value is blank; null (leave unchanged) otherwise. */
    private static String fill(String value, String current, boolean created) {
        if (value == null) return null;
        if (!created && current != null && !current.isBlank()) return null;
        if (value.equals(current)) return null;
        return value;
    }

    private String companyIdFor(CrmContext ctx, String name, Map<String, String> cache) {
        String key = name.toLowerCase(Locale.ROOT);
        String id = cache.get(key);
        if (id != null) return id;
        CrmCompany found = byName(ctx, name);
        id = found != null ? found.getId() : companies.create(ctx, new CompanyRequest(null, name, null, null, null,
                null, null, null, null, null, null, null, null, null, null)).id();
        cache.put(key, id);
        return id;
    }

    private CrmCompany byName(CrmContext ctx, String name) {
        return mongo.findOne(new Query(Criteria.where("workspaceId").is(ctx.workspaceId()).and("name")
                .regex(exactly(name))), CrmCompany.class);
    }

    private CrmCompany byDomain(CrmContext ctx, String domain) {
        return mongo.findOne(new Query(Criteria.where("workspaceId").is(ctx.workspaceId()).and("domain")
                .regex(exactly(domain))), CrmCompany.class);
    }

    private static Pattern exactly(String s) {
        return Pattern.compile("^" + Pattern.quote(s.trim()) + "$", Pattern.CASE_INSENSITIVE);
    }

    // --- cells -----------------------------------------------------------------------------------------------

    /** The option's key for a cell holding its key or its label (any case); the cell itself when the list is empty. */
    private static String pick(String cell, List<PicklistItem> items, String column) {
        if (cell == null || items == null || items.isEmpty()) return cell;
        for (PicklistItem i : items) {
            if (i.active() && (i.key().equalsIgnoreCase(cell) || cell.equalsIgnoreCase(i.label()))) return i.key();
        }
        throw new ValidationException(column, "is not an active option");
    }

    private static BigDecimal number(String cell, String column) {
        if (cell == null) return null;
        try {
            return new BigDecimal(cell.replace(",", ""));
        } catch (NumberFormatException e) {
            throw new ValidationException(column, "must be a number");
        }
    }

    /** A custom field's column; MULTI_SELECT cells hold options separated by ";". */
    private record Column(int index, boolean multi) {}

    /** Active custom fields of the entity by key, mapped to their column. */
    private Map<String, Column> customColumns(CrmContext ctx, TargetType entity, Sheet sheet) {
        Map<String, CrmFieldDefinition> byKey = new HashMap<>();
        fields.list(ctx, entity, false).forEach(d -> byKey.put(d.getKey(), d));
        Map<String, Column> out = new LinkedHashMap<>();
        for (int c = 0; c < sheet.header.size(); c++) {
            String h = sheet.header.get(c);
            Matcher m = KEYED.matcher(h);
            CrmFieldDefinition d = byKey.get(m.matches() ? m.group(1) : h);
            if (d != null) out.put(d.getKey(), new Column(c, d.getType() == FieldType.MULTI_SELECT));
        }
        return out;
    }

    private static Map<String, Object> customValues(Row r, Map<String, Column> columns) {
        Map<String, Object> out = new LinkedHashMap<>();
        columns.forEach((key, c) -> {
            String v = r.at(c.index());
            if (v == null) return;
            out.put(key, c.multi() ? Arrays.stream(v.split(";")).map(String::trim).filter(x -> !x.isEmpty()).toList()
                    : v);
        });
        return out;
    }

    private static String message(RuntimeException e) {
        if (e instanceof ValidationException v && v.fields() != null && !v.fields().isEmpty()) {
            return v.fields().entrySet().stream().map(x -> x.getKey() + " " + x.getValue())
                    .collect(Collectors.joining("; "));
        }
        return e.getMessage();
    }

    // --- the file --------------------------------------------------------------------------------------------

    private static Sheet read(MultipartFile file, String required, int limit) {
        if (file == null) throw new ValidationException("file", "is required");
        String text;
        try {
            text = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ValidationException("file", "could not be read");
        }
        List<List<String>> rows = Csv.parse(text);
        List<String> header = rows.isEmpty() ? List.of() : rows.get(0).stream().map(String::trim).toList();
        if (header.stream().noneMatch(h -> h.equalsIgnoreCase(required))) {
            throw CrmErrors.badRequest("IMPORT_BAD_HEADER", "The file has no " + required + " column",
                    Map.of("missing", required));
        }
        List<List<String>> data = rows.subList(1, rows.size());
        if (data.size() > limit) {
            throw CrmErrors.badRequest("IMPORT_TOO_LARGE", "The file has more than " + limit + " rows",
                    Map.of("limit", String.valueOf(limit)));
        }
        return new Sheet(header, data);
    }

    private record Sheet(List<String> header, List<List<String>> rows) {
        int column(String name) {
            for (int i = 0; i < header.size(); i++) if (header.get(i).equalsIgnoreCase(name)) return i;
            return -1;
        }
    }

    private record Row(Sheet sheet, List<String> cells) {
        /** The trimmed cell of the named column; null when the column is absent or the cell blank. */
        String get(String column) {
            return at(sheet.column(column));
        }

        String at(int c) {
            if (c < 0 || c >= cells.size()) return null;
            String v = cells.get(c).trim();
            return v.isEmpty() ? null : v;
        }
    }

    private static final class Tally {
        final int total;
        int created;
        int updated;
        int skipped;
        final List<ImportResult.RowError> errors = new ArrayList<>();

        Tally(int total) {
            this.total = total;
        }

        void error(int row, String message) {
            errors.add(new ImportResult.RowError(row, message));
        }

        ImportResult result() {
            return new ImportResult(total, created, updated, skipped, errors);
        }
    }
}
