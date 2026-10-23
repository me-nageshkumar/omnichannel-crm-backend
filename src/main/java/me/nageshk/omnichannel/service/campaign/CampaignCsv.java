package me.nageshk.omnichannel.service.campaign;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.service.contact.ContactCsv;
import me.nageshk.omnichannel.service.contact.ContactImportService;

/** Reads the CSV file of a campaign audience with the limits of the contact import. */
final class CampaignCsv {

    /** The trimmed header row and the data rows, each padded to the header length. */
    record Parsed(List<String> headers, List<List<String>> rows) {
    }

    private CampaignCsv() {
    }

    static Parsed parse(byte[] bytes) {
        if (bytes.length > ContactImportService.MAX_BYTES) throw new ValidationException("file", "is larger than 5 MB");
        List<ContactCsv.Row> all = ContactCsv.parse(new String(bytes, StandardCharsets.UTF_8));
        if (all.isEmpty()) throw new ValidationException("file", "is empty; a header row is required");
        if (all.size() - 1 > ContactImportService.MAX_ROWS) {
            throw new ValidationException("file", "has more than " + ContactImportService.MAX_ROWS + " rows");
        }
        List<String> headers = all.get(0).fields().stream().map(String::trim).toList();
        List<List<String>> rows = new ArrayList<>();
        for (ContactCsv.Row r : all.subList(1, all.size())) {
            List<String> cells = new ArrayList<>(r.fields());
            while (cells.size() < headers.size()) cells.add("");
            rows.add(cells);
        }
        return new Parsed(headers, rows);
    }
}
