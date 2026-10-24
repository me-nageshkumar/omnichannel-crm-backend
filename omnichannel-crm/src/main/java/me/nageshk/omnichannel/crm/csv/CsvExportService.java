package me.nageshk.omnichannel.crm.csv;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.common.CrmPage;
import me.nageshk.omnichannel.crm.common.Ref;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.company.Address;
import me.nageshk.omnichannel.crm.company.CrmCompanyService;
import me.nageshk.omnichannel.crm.company.dto.CompanySearchRequest;
import me.nageshk.omnichannel.crm.company.dto.CompanyView;
import me.nageshk.omnichannel.crm.contact.CrmContactService;
import me.nageshk.omnichannel.crm.contact.dto.ContactSearchRequest;
import me.nageshk.omnichannel.crm.contact.dto.CrmContactView;
import me.nageshk.omnichannel.crm.deal.DealService;
import me.nageshk.omnichannel.crm.deal.dto.DealSearchRequest;
import me.nageshk.omnichannel.crm.deal.dto.DealView;
import me.nageshk.omnichannel.crm.field.CrmFieldDefinition;
import me.nageshk.omnichannel.crm.field.FieldDefinitionService;
import me.nageshk.omnichannel.crm.field.FieldType;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import me.nageshk.omnichannel.crm.pipeline.CrmPipelineService;
import me.nageshk.omnichannel.crm.pipeline.CrmStage;
import org.springframework.stereotype.Service;

/**
 * Streamed CSV export of contacts, companies and deals (spec §6.7): the list's own search with the request's filters
 * and sort, read in pages of {@value #PAGE_SIZE} up to {@value #MAX_ROWS} rows, standard columns then every active
 * custom field as {@code label (key)}. The permission check and the first page run before any byte is written, so a
 * refused or invalid export is an ordinary error response.
 */
@Service
public class CsvExportService {

    static final int PAGE_SIZE = 1000;
    static final int MAX_ROWS = 50_000;

    private static final List<String> CONTACT_COLUMNS = List.of("First name", "Last name", "WhatsApp number",
            "Channel", "Email", "Job title", "Company", "Owner", "Lifecycle", "Source", "Open deals",
            "Open deal amount", "Created", "Last activity");
    private static final List<String> COMPANY_COLUMNS = List.of("Name", "Domain", "Website", "Industry", "Size",
            "Annual revenue", "Phone", "Email", "City", "Country", "Owner", "Contacts", "Open deal amount", "Created");
    private static final List<String> DEAL_COLUMNS = List.of("Title", "Pipeline", "Stage", "Status", "Amount",
            "Currency", "Probability", "Expected close", "Contact", "WhatsApp number", "Company", "Owner", "Source",
            "Lost reason", "Created", "Closed", "Last activity");

    /** Writes the CSV; handed out only after the checks passed. */
    @FunctionalInterface
    public interface CsvExport {
        void writeTo(OutputStream out) throws IOException;
    }

    private final CrmContactService contacts;
    private final CrmCompanyService companies;
    private final DealService deals;
    private final CrmPipelineService pipelines;
    private final FieldDefinitionService fields;

    CsvExportService(CrmContactService contacts, CrmCompanyService companies, DealService deals,
            CrmPipelineService pipelines, FieldDefinitionService fields) {
        this.contacts = contacts;
        this.companies = companies;
        this.deals = deals;
        this.pipelines = pipelines;
        this.fields = fields;
    }

    public CsvExport contacts(CrmContext ctx, ContactSearchRequest r) {
        ctx.require("crm:export");
        return prepare(ctx, TargetType.CONTACT, CONTACT_COLUMNS, n -> contacts.search(ctx,
                new ContactSearchRequest(r.q(), r.ownerId(), r.lifecycleStatus(), r.companyId(), r.pipelineId(),
                        r.sort(), r.dir(), n, PAGE_SIZE), PAGE_SIZE),
                c -> List.of(s(c.firstName()), s(c.lastName()), s(c.phone()),
                        c.channel() == null ? "" : s(c.channel().number()), s(c.email()), s(c.jobTitle()),
                        name(c.company()), name(c.owner()), s(c.lifecycleStatus()), s(c.source()),
                        String.valueOf(c.openDealCount()), money(c.openDealAmount()), time(c.creationTime()),
                        time(c.lastActivityAt())),
                CrmContactView::customFields);
    }

    public CsvExport companies(CrmContext ctx, CompanySearchRequest r) {
        ctx.require("crm:export");
        return prepare(ctx, TargetType.COMPANY, COMPANY_COLUMNS, n -> companies.search(ctx,
                new CompanySearchRequest(r.q(), r.ownerId(), r.industry(), r.sort(), r.dir(), n, PAGE_SIZE), PAGE_SIZE),
                c -> {
                    Address a = c.address();
                    return List.of(s(c.name()), s(c.domain()), s(c.website()), s(c.industry()), s(c.size()),
                            money(c.annualRevenue()), s(c.phone()), s(c.email()), a == null ? "" : s(a.city()),
                            a == null ? "" : s(a.country()), name(c.owner()), String.valueOf(c.contactCount()),
                            money(c.openDealAmount()), time(c.creationTime()));
                },
                CompanyView::customFields);
    }

    public CsvExport deals(CrmContext ctx, DealSearchRequest r) {
        ctx.require("crm:export");
        Map<String, String> pipelineNames = new HashMap<>();
        Map<String, String> stageNames = new HashMap<>();
        for (CrmPipeline p : pipelines.list(ctx)) {
            pipelineNames.put(p.getId(), p.getName());
            for (CrmStage st : p.getStages()) stageNames.put(p.getId() + "/" + st.getId(), st.getName());
        }
        return prepare(ctx, TargetType.DEAL, DEAL_COLUMNS, n -> deals.search(ctx,
                new DealSearchRequest(r.pipelineId(), r.stageId(), r.status(), r.ownerId(), r.companyId(),
                        r.crmContactId(), r.q(), r.sort(), r.dir(), n, PAGE_SIZE), PAGE_SIZE),
                d -> List.of(s(d.title()), s(pipelineNames.get(d.pipelineId())),
                        s(stageNames.get(d.pipelineId() + "/" + d.stageId())),
                        d.status() == null ? "" : d.status().name(), money(d.amount()), s(d.currency()),
                        d.probability() == null ? "" : String.valueOf(d.probability()), s(d.expectedCloseDate()),
                        d.primaryContact() == null ? "" : s(d.primaryContact().name()),
                        d.primaryContact() == null ? "" : s(d.primaryContact().phone()), name(d.company()),
                        name(d.owner()), s(d.source()), s(d.lostReason()), time(d.creationTime()),
                        time(d.closedAt()), time(d.lastActivityAt())),
                DealView::customFields);
    }

    private <T> CsvExport prepare(CrmContext ctx, TargetType entity, List<String> columns, IntFunction<CrmPage<T>> pager,
            Function<T, List<String>> cells, Function<T, Map<String, Object>> customFields) {
        List<CrmFieldDefinition> defs = fields.list(ctx, entity, false);
        CrmPage<T> first = pager.apply(0);
        return out -> {
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            List<String> header = new ArrayList<>(columns);
            defs.forEach(f -> header.add(f.getLabel() + " (" + f.getKey() + ")"));
            w.write(Csv.BOM);
            w.write(Csv.row(header));
            int written = 0;
            List<T> items = first.items();
            for (int page = 0; ; ) {
                for (T item : items) {
                    if (written >= MAX_ROWS) break;
                    List<String> line = new ArrayList<>(cells.apply(item));
                    Map<String, Object> values = customFields.apply(item);
                    for (CrmFieldDefinition f : defs) line.add(customCell(f, values == null ? null : values.get(f.getKey())));
                    w.write(Csv.row(line));
                    written++;
                }
                w.flush();
                if (written >= MAX_ROWS || items.size() < PAGE_SIZE) break;
                items = pager.apply(++page).items();
                if (items.isEmpty()) break;
            }
            w.flush();
        };
    }

    /** MULTI_SELECT joined by "; ", numbers without trailing zeros, everything else as stored (dates are ISO text). */
    static String customCell(CrmFieldDefinition def, Object value) {
        if (value == null) return "";
        if (value instanceof Collection<?> c) return c.stream().map(String::valueOf).collect(Collectors.joining("; "));
        if ((def.getType() == FieldType.NUMBER || def.getType() == FieldType.CURRENCY) && value instanceof Number n) {
            return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    private static String s(String v) {
        return v == null ? "" : v;
    }

    private static String name(Ref r) {
        return r == null || r.name() == null ? "" : r.name();
    }

    private static String money(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private static String time(Instant v) {
        return v == null ? "" : v.toString();
    }
}
