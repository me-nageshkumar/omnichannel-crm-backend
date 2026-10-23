package me.nageshk.omnichannel.service.inbox;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(InboxIngestService.preview("revoked", null, null)).isEqualTo("This message was deleted");
        assertThat(InboxIngestService.preview("whatever", null, null)).isEqualTo("Unsupported message");
        assertThat(InboxIngestService.preview("multi_vcard", null, null)).isEqualTo("👤 Contact card");
        assertThat(InboxIngestService.preview("image", "caption", null)).isEqualTo("caption");
        assertThat(InboxIngestService.preview("document", "report", "a.pdf")).isEqualTo("report");
        assertThat(InboxIngestService.preview("chat", "hello", null)).isEqualTo("hello");
    }
}
