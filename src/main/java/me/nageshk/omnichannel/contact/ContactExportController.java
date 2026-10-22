package me.nageshk.omnichannel.contact;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import me.nageshk.omnichannel.api.TooManyRowsException;
import me.nageshk.omnichannel.tenant.WorkspaceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** CSV export of a brand's contacts for a filter, streamed: {@code contact:export}. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/contacts/export")
public class ContactExportController {

    public record ExportRequest(ContactFilterSpec filter, String filterId) {}

    private final MongoTemplate mongo;
    private final ContactQuery query;
    private final ContactLabelRepository labels;
    private final ContactAttributeRepository attributes;
    private final WorkspaceRepository workspaces;
    private final Clock clock;
    private final QueryTimeouts timeouts;
    private volatile int exportLimit;

    public ContactExportController(MongoTemplate mongo, ContactQuery query, ContactLabelRepository labels,
            ContactAttributeRepository attributes, WorkspaceRepository workspaces, Clock clock,
            QueryTimeouts timeouts,
            @Value("${omnichannel.contacts.export-limit:100000}") int exportLimit) {
        this.mongo = mongo;
        this.query = query;
        this.labels = labels;
        this.attributes = attributes;
        this.workspaces = workspaces;
        this.clock = clock;
        this.timeouts = timeouts;
        this.exportLimit = exportLimit;
    }

    /** For tests: lowers the row limit. */
    void setExportLimit(int exportLimit) {
        this.exportLimit = exportLimit;
    }

    @PostMapping
    @PreAuthorize("@access.has(#workspaceId, 'contact:export')")
    public ResponseEntity<StreamingResponseBody> export(@PathVariable String workspaceId,
            @RequestBody(required = false) ExportRequest req) {
        ExportRequest r = req == null ? new ExportRequest(null, null) : req;
        Criteria criteria = query.forRequest(workspaceId, r.filter(), r.filterId());
        Query counting = new Query(criteria).maxTime(timeouts.count());
        long rows = QueryTimeouts.guarded(() -> mongo.count(counting, Contact.class));
        if (rows > exportLimit) {
            throw new TooManyRowsException("this export has " + rows + " contacts, over the limit of " + exportLimit
                    + "; narrow the filter");
        }
        Map<String, String> labelNames = labels.findByWorkspaceIdOrderByNameKey(workspaceId).stream()
                .collect(Collectors.toMap(ContactLabel::getId, ContactLabel::getName));
        List<ContactAttribute> columns = attributes.findByWorkspaceIdOrderByNameKey(workspaceId);
        String brand = workspaces.findById(workspaceId).map(w -> w.getName()).orElse("brand")
                .replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        String file = "contacts-" + (brand.isEmpty() ? "brand" : brand) + "-"
                + LocalDate.now(clock.withZone(ZoneOffset.UTC)).format(DateTimeFormatter.BASIC_ISO_DATE) + ".csv";
        Query q = new Query(criteria).with(Sort.by(Sort.Order.desc("lastMessageAt"), Sort.Order.asc("_id")));

        StreamingResponseBody body = out -> {
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            w.write('﻿');
            StringBuilder head = new StringBuilder("phone,name,number_hidden,source,labels");
            for (ContactAttribute a : columns) head.append(',').append(ContactCsv.cell(a.getName()));
            head.append(",created,last_message\r\n");
            w.write(head.toString());
            try (Stream<Contact> contacts = mongo.stream(q, Contact.class)) {
                contacts.forEach(c -> {
                    try {
                        w.write(line(c, labelNames, columns));
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            }
            w.flush();
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    private static String line(Contact c, Map<String, String> labelNames, List<ContactAttribute> columns) {
        StringBuilder s = new StringBuilder();
        s.append(ContactCsv.cell(c.getPhone())).append(',')
                .append(ContactCsv.cell(c.getName())).append(',')
                .append(c.getPhone() == null).append(',')
                .append(c.getSource() == null ? "" : c.getSource().name()).append(',')
                .append(ContactCsv.cell(c.getLabels().stream().map(labelNames::get).filter(n -> n != null).sorted()
                        .collect(Collectors.joining(";"))));
        for (ContactAttribute a : columns) s.append(',').append(ContactCsv.cell(c.getAttributes().get(a.getId())));
        s.append(',').append(c.getCreationTime() == null ? "" : c.getCreationTime().toString())
                .append(',').append(c.getLastMessageAt() == null ? "" : c.getLastMessageAt().toString())
                .append("\r\n");
        return s.toString();
    }
}
