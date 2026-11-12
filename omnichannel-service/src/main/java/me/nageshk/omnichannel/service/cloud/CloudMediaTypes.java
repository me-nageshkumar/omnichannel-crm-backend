package me.nageshk.omnichannel.service.cloud;

import java.util.Set;

/** Which MIME types a Cloud message header of each type accepts, and how large its file may be. */
public final class CloudMediaTypes {

    public static final Set<String> IMAGE = Set.of("image/jpeg", "image/png");
    public static final Set<String> VIDEO = Set.of("video/mp4", "video/3gpp");
    public static final Set<String> DOCUMENT = Set.of("application/pdf", "application/msword",
            "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain", "text/csv", "application/zip");

    private static final long MB = 1024L * 1024;

    private CloudMediaTypes() {}

    /** The MIME types a header of this type takes; empty for {@code text} or anything unknown. */
    public static Set<String> forHeader(String headerType) {
        if (headerType == null) return Set.of();
        return switch (headerType) {
            case "image" -> IMAGE;
            case "video" -> VIDEO;
            case "document" -> DOCUMENT;
            default -> Set.of();
        };
    }

    /** Image 5 MB (Meta's limit); video and document 16 MB (the app's multipart cap). */
    public static long maxBytes(String headerType) {
        return "image".equals(headerType) ? 5 * MB : 16 * MB;
    }

    /** "a JPEG or PNG", "an MP4 or 3GPP video", "a PDF, Office, text, CSV or ZIP document". */
    public static String describe(String headerType) {
        return switch (headerType == null ? "" : headerType) {
            case "image" -> "a JPEG or PNG";
            case "video" -> "an MP4 or 3GPP video";
            case "document" -> "a PDF, Office, text, CSV or ZIP document";
            default -> "a supported file";
        };
    }
}
