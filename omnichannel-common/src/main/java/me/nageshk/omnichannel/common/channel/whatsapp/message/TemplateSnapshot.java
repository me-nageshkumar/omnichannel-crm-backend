package me.nageshk.omnichannel.common.channel.whatsapp.message;

import java.util.List;

/** What a sent Meta template looked like to the customer, for the thread to draw. */
public record TemplateSnapshot(String name, String language, Header header, String body, String footer,
        List<Button> buttons) {

    /** {@code format} NONE | TEXT | IMAGE | VIDEO | DOCUMENT; {@code mediaId} our cloud media id, if any. */
    public record Header(String format, String text, String mediaId) {}

    public record Button(String type, String text) {}
}
