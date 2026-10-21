package me.nageshk.omnichannel.campaign;

import java.util.List;
import java.util.Map;

/** What an uploaded CSV looks like before a campaign is saved: its columns, a few rows and likely mappings. */
public record CsvPreview(List<String> headers, List<List<String>> sample, int rows, Suggested suggested) {

    /** The header equal to {@code phone}, and for each placeholder the header of the same name. */
    public record Suggested(String phone, Map<String, String> placeholders) {
    }
}
