package me.nageshk.omnichannel.service.template;

import java.util.Locale;
import java.util.Optional;

/** Detects a file's MIME type from its first bytes (not from what the browser says); no Tika, only the allowed formats. */
public final class MediaSniffer {

    /** How many leading bytes are looked at. */
    public static final int HEAD = 64;

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    private MediaSniffer() {}

    /** The MIME type of the file, or empty when it is not one of the supported formats. */
    public static Optional<String> detect(byte[] head, String fileName) {
        int n = Math.min(head.length, HEAD);
        String ext = extension(fileName);
        if (starts(head, n, 0xFF, 0xD8, 0xFF)) return Optional.of("image/jpeg");
        if (starts(head, n, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return Optional.of("image/png");
        if (startsAscii(head, n, 0, "%PDF-")) return Optional.of("application/pdf");
        if (n >= 12 && startsAscii(head, n, 4, "ftyp")) {
            if (startsAscii(head, n, 8, "M4A ")) return Optional.of("audio/mp4");
            if (startsAscii(head, n, 8, "3gp")) return Optional.of("video/3gpp");
            return Optional.of("video/mp4");
        }
        if (startsAscii(head, n, 0, "OggS")) return Optional.of("audio/ogg");
        if (startsAscii(head, n, 0, "ID3")) return Optional.of("audio/mpeg");
        if (starts(head, n, 0xFF, 0xF1) || starts(head, n, 0xFF, 0xF9)) return Optional.of("audio/aac");
        if (n >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0 && (head[1] & 0x06) != 0) {
            return Optional.of("audio/mpeg");
        }
        if (starts(head, n, 0x50, 0x4B, 0x03, 0x04)) {
            return switch (ext) {
                case "docx" -> Optional.of(DOCX);
                case "xlsx" -> Optional.of(XLSX);
                case "pptx" -> Optional.of(PPTX);
                case "zip" -> Optional.of("application/zip");
                default -> Optional.empty();
            };
        }
        if (starts(head, n, 0xD0, 0xCF, 0x11, 0xE0)) {
            return switch (ext) {
                case "doc" -> Optional.of("application/msword");
                case "xls" -> Optional.of("application/vnd.ms-excel");
                case "ppt" -> Optional.of("application/vnd.ms-powerpoint");
                default -> Optional.empty();
            };
        }
        if ((ext.equals("txt") || ext.equals("csv")) && noNul(head, n)) {
            return Optional.of(ext.equals("txt") ? "text/plain" : "text/csv");
        }
        return Optional.empty();
    }

    /** True when the head is an Ogg stream whose first 64 bytes contain {@code OpusHead}. */
    public static boolean isOpus(byte[] head) {
        int n = Math.min(head.length, HEAD);
        if (!startsAscii(head, n, 0, "OggS")) return false;
        for (int i = 0; i + "OpusHead".length() <= n; i++) {
            if (startsAscii(head, n, i, "OpusHead")) return true;
        }
        return false;
    }

    private static boolean starts(byte[] b, int n, int... expected) {
        if (n < expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if ((b[i] & 0xFF) != expected[i]) return false;
        }
        return true;
    }

    private static boolean startsAscii(byte[] b, int n, int offset, String text) {
        if (n < offset + text.length()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (b[offset + i] != (byte) text.charAt(i)) return false;
        }
        return true;
    }

    private static boolean noNul(byte[] b, int n) {
        for (int i = 0; i < n; i++) {
            if (b[i] == 0) return false;
        }
        return true;
    }

    /** Lower-case extension after the last dot, or the empty string. */
    private static String extension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
