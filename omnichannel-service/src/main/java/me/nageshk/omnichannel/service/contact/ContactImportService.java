package me.nageshk.omnichannel.service.contact;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactAttribute;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.contact.ContactLabel;
import me.nageshk.omnichannel.db.contact.ContactLabelRepository;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.contact.ContactSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Imports a CSV of contacts into one brand: creates new phones, adds to existing ones, never removes. */
@Service
public class ContactImportService {

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_ROWS = 20_000;
    public static final int MAX_PROBLEMS = 200;

    public record Problem(int row, String reason) {}

    public record Result(int created, int updated, int unchanged, int skipped, List<Problem> problems,
            List<String> unknownColumns, List<String> unknownLabels) {}

    private final ContactRepository contacts;
    private final ContactLabelRepository labels;
    private final ContactAttributeRepository attributes;

    public ContactImportService(ContactRepository contacts, ContactLabelRepository labels,
            ContactAttributeRepository attributes) {
        this.contacts = contacts;
        this.labels = labels;
        this.attributes = attributes;
    }

    public Result run(String workspaceId, String customerId, byte[] file) {
        if (file.length > MAX_BYTES) throw new ValidationException("file", "is larger than 5 MB");
        List<ContactCsv.Row> rows = ContactCsv.parse(new String(file, StandardCharsets.UTF_8));
        if (rows.isEmpty()) throw new ValidationException("file", "is empty; a header row with a phone column is required");
        if (rows.size() - 1 > MAX_ROWS) throw new ValidationException("file", "has more than " + MAX_ROWS + " rows");

        List<String> header = rows.get(0).fields().stream().map(h -> h.trim()).toList();
        Map<String, ContactAttribute> attributeByName = new HashMap<>();
        attributes.findByWorkspaceIdOrderByNameKey(workspaceId).forEach(a -> attributeByName.put(a.getNameKey(), a));
        Map<String, ContactLabel> labelByName = new HashMap<>();
        labels.findByWorkspaceIdOrderByNameKey(workspaceId).forEach(l -> labelByName.put(l.getNameKey(), l));

        int phoneCol = -1;
        int nameCol = -1;
        int labelsCol = -1;
        Map<Integer, ContactAttribute> attributeCols = new LinkedHashMap<>();
        List<String> unknownColumns = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i);
            String key = h.toLowerCase();
            if (h.isEmpty()) continue;
            if (key.equals("phone") && phoneCol < 0) phoneCol = i;
            else if (key.equals("name") && nameCol < 0) nameCol = i;
            else if (key.equals("labels") && labelsCol < 0) labelsCol = i;
            else if (attributeByName.containsKey(key) && !attributeCols.containsValue(attributeByName.get(key))) {
                attributeCols.put(i, attributeByName.get(key));
            } else unknownColumns.add(h);
        }
        if (phoneCol < 0) throw new ValidationException("file", "needs a header row with a phone column");

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int skipped = 0;
        List<Problem> problems = new ArrayList<>();
        Map<String, String> unknownLabels = new LinkedHashMap<>();
        for (ContactCsv.Row row : rows.subList(1, rows.size())) {
            String problem = null;
            String phone = Phones.normalise(cell(row, phoneCol).trim());
            String name = cell(row, nameCol).trim();
            Set<String> labelIds = new LinkedHashSet<>();
            Map<String, String> values = new LinkedHashMap<>();
            if (!Phones.isValid(phone)) problem = "phone must be 8 to 15 digits with the country code";
            else if (name.length() > ContactService.MAX_NAME) problem = "name is longer than " + ContactService.MAX_NAME + " characters";
            for (var e : attributeCols.entrySet()) {
                String v = cell(row, e.getKey()).trim();
                if (v.isEmpty()) continue;
                if (v.length() > ContactService.MAX_ATTRIBUTE_VALUE && problem == null) {
                    problem = e.getValue().getName() + " is longer than " + ContactService.MAX_ATTRIBUTE_VALUE + " characters";
                }
                values.put(e.getValue().getId(), v);
            }
            if (problem != null) {
                skipped++;
                if (problems.size() < MAX_PROBLEMS) problems.add(new Problem(row.number(), problem));
                continue;
            }
            for (String l : cell(row, labelsCol).split("[;,]")) {
                String t = l.trim();
                if (t.isEmpty()) continue;
                ContactLabel known = labelByName.get(t.toLowerCase());
                if (known != null) labelIds.add(known.getId());
                else unknownLabels.putIfAbsent(t.toLowerCase(), t);
            }
            switch (apply(workspaceId, customerId, phone, name, labelIds, values)) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
            }
        }
        return new Result(created, updated, unchanged, skipped, problems, unknownColumns,
                new ArrayList<>(unknownLabels.values()));
    }

    private enum Outcome { CREATED, UPDATED, UNCHANGED }

    private Outcome apply(String workspaceId, String customerId, String phone, String name, Set<String> labelIds,
            Map<String, String> values) {
        Contact c = contacts.findByWorkspaceIdAndPhone(workspaceId, phone).orElse(null);
        if (c == null) {
            c = new Contact();
            c.setWorkspaceId(workspaceId);
            c.setCustomerId(customerId);
            c.setPhone(phone);
            c.setSource(ContactSource.IMPORT);
            if (!name.isEmpty()) {
                c.setName(name);
                c.setNameEdited(true);
            }
            c.getLabels().addAll(labelIds);
            c.getAttributes().putAll(values);
            try {
                contacts.save(c);
                return Outcome.CREATED;
            } catch (DuplicateKeyException e) {
                c = contacts.findByWorkspaceIdAndPhone(workspaceId, phone).orElseThrow(() -> e);
            }
        }
        boolean changed = c.getLabels().addAll(labelIds);
        for (var e : values.entrySet()) {
            if (!e.getValue().equals(c.getAttributes().put(e.getKey(), e.getValue()))) changed = true;
        }
        if (!name.isEmpty() && !name.equals(c.getName())) {
            c.setName(name);
            c.setNameEdited(true);
            changed = true;
        }
        if (!changed) return Outcome.UNCHANGED;
        contacts.save(c);
        return Outcome.UPDATED;
    }

    private static String cell(ContactCsv.Row row, int col) {
        return col >= 0 && col < row.fields().size() ? row.fields().get(col) : "";
    }
}
