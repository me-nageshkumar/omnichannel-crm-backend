package me.nageshk.omnichannel.crm.csv;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * RFC 4180 CSV for spreadsheets (spec §6.7). Cells are quoted when they hold a comma, quote, CR, LF, tab or leading or
 * trailing space; a cell a spreadsheet would run as a formula (starting with {@code = + - @}, tab or CR) gets a
 * leading apostrophe, while plain numbers such as {@code -2} or {@code +9198…} are left alone.
 */
public final class Csv {

    /** Written before the first row so spreadsheets read the file as UTF-8. */
    public static final String BOM = "﻿";

    private static final Pattern NUMBER = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$");

    private Csv() {}

    public static String cell(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String v = raw;
        char first = v.charAt(0);
        if ((first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r')
                && !NUMBER.matcher(v).matches()) {
            v = "'" + v;
        }
        boolean quote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0
                || v.indexOf('\t') >= 0 || Character.isWhitespace(v.charAt(0))
                || Character.isWhitespace(v.charAt(v.length() - 1));
        return quote ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    /** The cells joined by commas, ending in CRLF. */
    public static String row(List<String> cells) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) b.append(',');
            b.append(cell(cells.get(i)));
        }
        return b.append("\r\n").toString();
    }

    /**
     * The rows of the text: a leading BOM is skipped, CRLF and LF both end a row, quoted fields may hold commas,
     * line breaks and doubled quotes, and rows whose cells are all blank are dropped.
     */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null) return rows;
        int i = text.startsWith(BOM) ? 1 : 0;
        int n = text.length();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        while (i < n) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                addUnlessBlank(rows, row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
            i++;
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            addUnlessBlank(rows, row);
        }
        return rows;
    }

    private static void addUnlessBlank(List<List<String>> rows, List<String> row) {
        for (String c : row) {
            if (!c.isBlank()) {
                rows.add(row);
                return;
            }
        }
    }
}
