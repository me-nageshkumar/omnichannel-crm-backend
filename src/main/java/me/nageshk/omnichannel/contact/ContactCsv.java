package me.nageshk.omnichannel.contact;

import java.util.ArrayList;
import java.util.List;

/** A small RFC 4180 reader and writer for contact files: BOM, quoted fields, any line ending, blank lines. */
public final class ContactCsv {

    /** A non-blank record and its 1-based position in the file (blank lines count, the header is 1). */
    public record Row(int number, List<String> fields) {}

    private ContactCsv() {}

    public static List<Row> parse(String text) {
        String s = text.startsWith("﻿") ? text.substring(1) : text;
        List<Row> rows = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        int number = 1;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"' && field.isEmpty() && !wasQuoted) {
                quoted = true;
                wasQuoted = true;
            } else if (ch == ',') {
                fields.add(field.toString());
                field.setLength(0);
                wasQuoted = false;
            } else if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                number = endRecord(rows, fields, field, wasQuoted, number);
                wasQuoted = false;
            } else {
                field.append(ch);
            }
        }
        if (!field.isEmpty() || wasQuoted || !fields.isEmpty()) endRecord(rows, fields, field, wasQuoted, number);
        return rows;
    }

    private static int endRecord(List<Row> rows, List<String> fields, StringBuilder field, boolean wasQuoted, int number) {
        boolean blank = fields.isEmpty() && field.isEmpty() && !wasQuoted;
        if (!blank) {
            fields.add(field.toString());
            rows.add(new Row(number, new ArrayList<>(fields)));
        }
        fields.clear();
        field.setLength(0);
        return number + 1;
    }

    /**
     * One cell for writing: quoted when it holds a comma, quote or line break; a cell that a spreadsheet
     * would run as a formula gets a leading apostrophe.
     */
    public static String cell(String value) {
        if (value == null || value.isEmpty()) return "";
        String v = value;
        char first = v.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') v = "'" + v;
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
