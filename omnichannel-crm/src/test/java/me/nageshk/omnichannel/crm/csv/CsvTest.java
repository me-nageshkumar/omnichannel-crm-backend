package me.nageshk.omnichannel.crm.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvTest {

    @Test
    void quotesCommasAndQuotes() {
        assertThat(Csv.cell("plain")).isEqualTo("plain");
        assertThat(Csv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(Csv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(Csv.cell("two\nlines")).isEqualTo("\"two\nlines\"");
        assertThat(Csv.cell(" padded")).isEqualTo("\" padded\"");
        assertThat(Csv.cell(null)).isEmpty();
        assertThat(Csv.row(Arrays.asList("a", null, "b,c"))).isEqualTo("a,,\"b,c\"\r\n");
    }

    @Test
    void formulaCellsGuarded() {
        assertThat(Csv.cell("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(Csv.cell("+cmd")).isEqualTo("'+cmd");
        assertThat(Csv.cell("-x")).isEqualTo("'-x");
        assertThat(Csv.cell("@here")).isEqualTo("'@here");
        assertThat(Csv.cell("=1,2")).isEqualTo("\"'=1,2\"");
        // plain numbers, phone numbers among them, are left alone
        assertThat(Csv.cell("-2")).isEqualTo("-2");
        assertThat(Csv.cell("+919900000101")).isEqualTo("+919900000101");
    }

    @Test
    void parseHandlesBomCrlfQuotedNewlines() {
        String text = Csv.BOM + "Name,Note\r\n\"Acme, Inc\",\"line one\nline \"\"two\"\"\"\r\nBeta,plain\n";
        assertThat(Csv.parse(text)).containsExactly(List.of("Name", "Note"),
                List.of("Acme, Inc", "line one\nline \"two\""), List.of("Beta", "plain"));
    }

    @Test
    void blankLinesDropped() {
        assertThat(Csv.parse("a,b\r\n\r\n,\r\nc,d\r\n\r\n")).containsExactly(List.of("a", "b"), List.of("c", "d"));
        assertThat(Csv.parse("")).isEmpty();
    }
}
