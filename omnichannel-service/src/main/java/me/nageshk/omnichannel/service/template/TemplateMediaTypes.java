package me.nageshk.omnichannel.service.template;

import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.db.template.TemplateType;

/** Which MIME types each media template type accepts, and how to name them in error messages. */
final class TemplateMediaTypes {

    private static final Map<TemplateType, Set<String>> ALLOWED = Map.of(
            TemplateType.IMAGE, Set.of("image/jpeg", "image/png"),
            TemplateType.VIDEO, Set.of("video/mp4", "video/3gpp"),
            TemplateType.AUDIO, Set.of("audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg"),
            TemplateType.DOCUMENT, Set.of("application/pdf", "application/msword", "application/vnd.ms-excel",
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "text/plain", "text/csv", "application/zip"));

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("image/jpeg", "a JPEG image"),
            Map.entry("image/png", "a PNG image"),
            Map.entry("video/mp4", "an MP4 video"),
            Map.entry("video/3gpp", "a 3GPP video"),
            Map.entry("audio/mpeg", "an MP3 audio file"),
            Map.entry("audio/mp4", "an M4A audio file"),
            Map.entry("audio/aac", "an AAC audio file"),
            Map.entry("audio/ogg", "an Ogg audio file"),
            Map.entry("application/pdf", "a PDF"),
            Map.entry("application/msword", "a Word document"),
            Map.entry("application/vnd.ms-excel", "an Excel workbook"),
            Map.entry("application/vnd.ms-powerpoint", "a PowerPoint deck"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "a Word document"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "an Excel workbook"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "a PowerPoint deck"),
            Map.entry("text/plain", "a text file"),
            Map.entry("text/csv", "a CSV file"),
            Map.entry("application/zip", "a ZIP archive"));

    private TemplateMediaTypes() {}

    static boolean takesMedia(TemplateType type) {
        return ALLOWED.containsKey(type);
    }

    static boolean allows(TemplateType type, String mime) {
        return ALLOWED.getOrDefault(type, Set.of()).contains(mime);
    }

    /** "a PDF", "an MP4 video"; a generic phrase for a type this does not know. */
    static String describe(String mime) {
        return NAMES.getOrDefault(mime, "this file");
    }

    /** "an image", "a video", "an audio", "a document". */
    static String describe(TemplateType type) {
        return switch (type) {
            case IMAGE -> "an image";
            case AUDIO -> "an audio";
            case VIDEO -> "a video";
            default -> "a document";
        };
    }
}
