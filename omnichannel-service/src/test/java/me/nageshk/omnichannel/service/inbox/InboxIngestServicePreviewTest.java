package me.nageshk.omnichannel.service.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import org.junit.jupiter.api.Test;

class InboxIngestServicePreviewTest {

    @Test
    void previewLabelsByType() {
        assertThat(InboxIngestService.preview("image", null, null)).isEqualTo("📷 Photo");
        assertThat(InboxIngestService.preview("video", "", null)).isEqualTo("🎥 Video");
        assertThat(InboxIngestService.preview("audio", " ", null)).isEqualTo("🎤 Voice message");
        assertThat(InboxIngestService.preview("ptt", null, null)).isEqualTo("🎤 Voice message");
        assertThat(InboxIngestService.preview("document", null, "a.pdf")).isEqualTo("📄 a.pdf");
        assertThat(InboxIngestService.preview("document", null, null)).isEqualTo("📄 Document");
        assertThat(InboxIngestService.preview("sticker", null, null)).isEqualTo("Sticker");
        assertThat(InboxIngestService.preview("location", null, null)).isEqualTo("📍 Location");
        assertThat(InboxIngestService.preview("vcard", null, null)).isEqualTo("👤 Contact card");
        assertThat(InboxIngestService.preview("poll_creation", null, null)).isEqualTo("📊 Poll");
        assertThat(InboxIngestService.preview("call_log", null, null)).isEqualTo("📞 Call");
        assertThat(InboxIngestService.preview("notification_template", "", null)).isEqualTo("WhatsApp notice");
        assertThat(InboxIngestService.preview("revoked", null, null)).isEqualTo("This message was deleted");
        assertThat(InboxIngestService.preview("whatever", null, null)).isEqualTo("Unsupported message");
        assertThat(InboxIngestService.preview("multi_vcard", null, null)).isEqualTo("👤 Contact card");
        assertThat(InboxIngestService.preview("image", "caption", null)).isEqualTo("caption");
        assertThat(InboxIngestService.preview("document", "report", "a.pdf")).isEqualTo("report");
        assertThat(InboxIngestService.preview("chat", "hello", null)).isEqualTo("hello");
    }

    private static final String WIFE = "BEGIN:VCARD\nVERSION:3.0\nN:;Wife;;;\nFN:Wife\n"
            + "TEL;type=CELL;waid=919799750743:+91 97997 50743\nEND:VCARD";
    private static final String ASHA = "BEGIN:VCARD\nVERSION:3.0\nFN:Asha\nTEL:+91 98100 00001\nEND:VCARD";

    @Test
    void aContactCardPreviewsAsTheNameOnIt() {
        assertThat(InboxIngestService.preview("vcard", WIFE, null)).isEqualTo("👤 Wife");
    }

    @Test
    void severalContactCardsPreviewAsHowMany() {
        assertThat(InboxIngestService.preview("multi_vcard", WIFE + "\n" + ASHA, null)).isEqualTo("👤 2 contacts");
    }

    @Test
    void aCardWithoutANamePreviewsAsAContactCard() {
        assertThat(InboxIngestService.preview("vcard", "BEGIN:VCARD\nTEL:+1\nEND:VCARD", null))
                .isEqualTo("👤 Contact card");
    }

    @Test
    void whatWhatsAppWebCannotShowHasALabel() {
        assertThat(InboxIngestService.preview("biz_content_placeholder", null, null)).isEqualTo("💼 Business message");
        assertThat(InboxIngestService.preview("unknown", null, null)).isEqualTo("Message to view on the phone");
        assertThat(InboxIngestService.preview("newsletter_follower_invite", null, null)).isEqualTo("📢 Channel invite");
    }

    @Test
    void aVerificationCodeOnlyThePhoneHasPreviewsAsSuch() {
        WaWebMessage m = new WaWebMessage();
        m.setType("biz_content_placeholder");
        m.setBusinessTag("AUTHENTICATION");
        assertThat(InboxIngestService.preview(m)).isEqualTo("🔒 Verification code");
        m.setBusinessTag("MARKETING");
        assertThat(InboxIngestService.preview(m)).isEqualTo("💼 Business message");
    }

    @Test
    void aChannelInvitePreviewsWithTheChannelName() {
        assertThat(InboxIngestService.preview("newsletter_follower_invite", "Speak English", null))
                .isEqualTo("📢 Channel invite: Speak English");
    }

    @Test
    void previewKeepsTheWholeBody() {
        String whole = "x".repeat(300);
        assertThat(InboxIngestService.preview("chat", whole, null)).isEqualTo(whole);
    }

    @Test
    void thumbnailOfAnyNonTextTypeIsNotShown() {
        String thumbnail = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRof".repeat(10);
        assertThat(InboxIngestService.preview("product", thumbnail, null)).isEqualTo("Unsupported message");
    }

    @Test
    void longTokenCaptionIsKept() {
        String hash = "a3f9".repeat(16);
        assertThat(InboxIngestService.preview("document", hash, "a.pdf")).isEqualTo(hash);
    }

    @Test
    void thumbnailBodyPreviewsAsTheLabel() {
        String thumbnail = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRof".repeat(10);
        assertThat(InboxIngestService.preview("image", thumbnail, null)).isEqualTo("📷 Photo");
        assertThat(InboxIngestService.preview("video", thumbnail, null)).isEqualTo("🎥 Video");
        assertThat(InboxIngestService.preview("document", thumbnail, "a.pdf")).isEqualTo("📄 a.pdf");
    }
}
