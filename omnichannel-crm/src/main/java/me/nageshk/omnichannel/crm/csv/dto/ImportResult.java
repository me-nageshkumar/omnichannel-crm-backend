package me.nageshk.omnichannel.crm.csv.dto;

import java.util.List;

/** What an import did; {@code row} of an error is the 1-based data row (the header is not counted). */
public record ImportResult(int total, int created, int updated, int skipped, List<RowError> errors) {

    public record RowError(int row, String message) {}
}
