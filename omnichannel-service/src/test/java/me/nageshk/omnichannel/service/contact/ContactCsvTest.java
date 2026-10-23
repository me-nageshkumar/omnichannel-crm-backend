package me.nageshk.omnichannel.service.contact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContactCsvTest {

    private static List<List<String>> fields(String csv) {
        return ContactCsv.parse(csv).stream().map(ContactCsv.Row::fields).toList();
    }

    @Test
    void parsesPlainRowsWithAnyLineEnding() {
        assertThat(fields("a,b\r\nc,d\ne,f\rg,h\n")).containsExactly(
                List.of("a", "b"), List.of("c", "d"), List.of("e", "f"), List.of("g", "h"));
    }

    @Test
    void stripsBomAndKeepsRowNumbersAcrossBlankLines() {
        List<ContactCsv.Row> rows = ContactCsv.parse("﻿phone,name\n\n919812345678,A\n\n\n919812345679,B");
        assertThat(rows).extracting(ContactCsv.Row::number).containsExactly(1, 3, 6);
        assertThat(rows.get(0).fields()).containsExactly("phone", "name");
    }

    @Test
    void quotedFieldsKeepCommasQuotesAndNewlines() {
        assertThat(fields("\"Rao, Asha\",\"say \"\"hi\"\"\",\"two\nlines\"\n"))
                .containsExactly(List.of("Rao, Asha", "say \"hi\"", "two\nlines"));
    }

    @Test
    void emptyFieldsAreKept() {
        assertThat(fields("a,,c\n,,\n")).containsExactly(List.of("a", "", "c"), List.of("", "", ""));
    }

    @Test
    void quoteOnlyWhenNeeded() {
        assertThat(ContactCsv.cell("plain")).isEqualTo("plain");
        assertThat(ContactCsv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(ContactCsv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(ContactCsv.cell("two\nlines")).isEqualTo("\"two\nlines\"");
        assertThat(ContactCsv.cell(null)).isEmpty();
    }

    @Test
    void formulaLookingCellsAreDefused() {
        assertThat(ContactCsv.cell("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(ContactCsv.cell("@cmd")).isEqualTo("'@cmd");
    }
}
