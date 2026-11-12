package me.nageshk.omnichannel.service.cloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessage;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive.Action;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive.ActionButton;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive.Body;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive.CtaParameters;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive.Footer;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive.Header;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive.MediaRef;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive.Reply;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive.Row;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive.Section;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Text;
import org.junit.jupiter.api.Test;

/** Spec §5.2: Meta's limits and our reply-match rules, with JSON-path error keys. */
class CloudMessageValidatorTest {

    private static final CloudMessageValidator.MediaLookup NO_MEDIA = (ws, id) -> Optional.empty();
    private static final CloudMessageValidator.MediaLookup PNG = (ws, id) ->
            "img1".equals(id) ? Optional.of("image/png") : Optional.empty();

    private final CloudMessageValidator validator = new CloudMessageValidator();

    // ---- builders ----

    static CloudMessage text(String to, String body) {
        return new CloudMessage("whatsapp-web", "individual", to, "text", new Text(true, body), null, null);
    }

    static CloudMessage interactive(Interactive i) {
        return new CloudMessage("whatsapp-web", "individual", "919900000001", "interactive", null, i, null);
    }

    static ActionButton reply(String id, String title) {
        return new ActionButton("reply", new Reply(id, title));
    }

    static CloudMessage button(ActionButton... buttons) {
        return button(null, buttons);
    }

    static CloudMessage button(Header header, ActionButton... buttons) {
        return interactive(new Interactive("button", header, new Body("Hi"), null,
                new Action(List.of(buttons), null, null, null, null)));
    }

    static Row row(String id, String title, String description) {
        return new Row(id, title, description);
    }

    static Section section(String title, Row... rows) {
        return new Section(title, List.of(rows));
    }

    static CloudMessage list(Section... sections) {
        return interactive(new Interactive("list", null, new Body("Pick"), null,
                new Action(null, "Options", List.of(sections), null, null)));
    }

    static CloudMessage cta(String text, String url) {
        return interactive(new Interactive("cta_url", null, new Body("See"), null,
                new Action(null, null, null, "cta_url", new CtaParameters(text, url))));
    }

    static CloudMessage location(String body) {
        return interactive(new Interactive("location_request_message", null, new Body(body), null,
                new Action(null, null, null, "send_location", null)));
    }

    Map<String, String> fieldsOf(CloudMessage m) {
        return fieldsOf(m, NO_MEDIA);
    }

    Map<String, String> fieldsOf(CloudMessage m, CloudMessageValidator.MediaLookup media) {
        try {
            validator.validate("w1", m, media, true);
            return Map.of();
        } catch (ValidationException e) {
            return e.fields();
        }
    }

    // ---- envelope ----

    @Test
    void acceptsMetasButtonExampleAndNormalisesTo() {
        CloudMessage ok = validator.validate("w1", text("+91 99000-00001", "hi"), NO_MEDIA, true);
        assertThat(ok.to()).isEqualTo("919900000001");
    }

    @Test
    void rejectsUnknownMessagingProduct() {
        CloudMessage m = new CloudMessage("sms", "individual", "919900000001", "text", new Text(null, "hi"), null, null);
        assertThat(fieldsOf(m)).containsEntry("messaging_product", "must be whatsapp or whatsapp-web");
    }

    @Test
    void rejectsRecipientTypeOtherThanIndividual() {
        CloudMessage m = new CloudMessage("whatsapp-web", "group", "919900000001", "text", new Text(null, "hi"), null, null);
        assertThat(fieldsOf(m)).containsEntry("recipient_type", "must be individual");
    }

    @Test
    void rejectsABadPhoneWhenToIsAPhone() {
        assertThat(fieldsOf(text("12", "hi"))).containsEntry("to", me.nageshk.omnichannel.service.contact.Phones.MESSAGE);
    }

    @Test
    void acceptsAnyNonBlankToWhenNotAPhone() {
        CloudMessage ok = validator.validate("w1", text("123456789@lid", "hi"), NO_MEDIA, false);
        assertThat(ok.to()).isEqualTo("123456789@lid");
        assertThatThrownBy(() -> validator.validate("w1", text(" ", "hi"), NO_MEDIA, false))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsUnknownType() {
        CloudMessage m = new CloudMessage("whatsapp-web", null, "919900000001", "image", null, null, null);
        assertThat(fieldsOf(m)).containsEntry("type", "must be text or interactive");
    }

    @Test
    void requiresTheMatchingSubObjectAndNoOther() {
        CloudMessage noText = new CloudMessage("whatsapp-web", null, "919900000001", "text", null, null, null);
        assertThat(fieldsOf(noText)).containsEntry("text", "is required");
        CloudMessage both = new CloudMessage("whatsapp-web", null, "919900000001", "text", new Text(null, "hi"),
                new Interactive("button", null, new Body("x"), null, null), null);
        assertThat(fieldsOf(both)).containsEntry("interactive", "must be absent");
    }

    @Test
    void textBodyIsRequiredAndCapped() {
        assertThat(fieldsOf(text("919900000001", ""))).containsEntry("text.body", "is required");
        assertThat(fieldsOf(text("919900000001", "a".repeat(4097)))).containsEntry("text.body", "must be at most 4096 characters");
    }

    // ---- interactive common ----

    @Test
    void rejectsUnknownInteractiveType() {
        CloudMessage m = interactive(new Interactive("flow", null, new Body("x"), null, new Action(null, null, null, null, null)));
        assertThat(fieldsOf(m)).containsEntry("interactive.type", "must be button, list, cta_url or location_request_message");
    }

    @Test
    void bodyIsRequiredAndCappedPerKind() {
        CloudMessage noBody = interactive(new Interactive("button", null, null, null,
                new Action(List.of(reply("a", "A")), null, null, null, null)));
        assertThat(fieldsOf(noBody)).containsEntry("interactive.body.text", "is required");
        CloudMessage longButton = interactive(new Interactive("button", null, new Body("a".repeat(1025)), null,
                new Action(List.of(reply("a", "A")), null, null, null, null)));
        assertThat(fieldsOf(longButton)).containsEntry("interactive.body.text", "must be at most 1024 characters");
        CloudMessage longList = interactive(new Interactive("list", null, new Body("a".repeat(4096)), null,
                new Action(null, "Go", List.of(section("S", row("r", "R", null))), null, null)));
        assertThat(fieldsOf(longList)).doesNotContainKey("interactive.body.text");
    }

    @Test
    void footerIsCapped() {
        CloudMessage m = interactive(new Interactive("button", null, new Body("Hi"), new Footer("f".repeat(61)),
                new Action(List.of(reply("a", "A")), null, null, null, null)));
        assertThat(fieldsOf(m)).containsEntry("interactive.footer.text", "must be at most 60 characters");
    }

    @Test
    void textHeaderNeedsTextWithin60() {
        assertThat(fieldsOf(button(new Header("text", "", null, null, null), reply("a", "A"))))
                .containsEntry("interactive.header.text", "is required");
        assertThat(fieldsOf(button(new Header("text", "h".repeat(61), null, null, null), reply("a", "A"))))
                .containsEntry("interactive.header.text", "must be at most 60 characters");
    }

    @Test
    void mediaHeaderNeedsAnUploadedIdNotALink() {
        Header link = new Header("image", null, new MediaRef(null, "https://x/y.png"), null, null);
        assertThat(fieldsOf(button(link, reply("a", "A")), PNG))
                .containsEntry("interactive.header.image.link", "is not supported; upload the file and use its id");
        Header none = new Header("image", null, null, null, null);
        assertThat(fieldsOf(button(none, reply("a", "A")), PNG)).containsEntry("interactive.header.image.id", "is required");
        Header unknown = new Header("image", null, new MediaRef("nope", null), null, null);
        assertThat(fieldsOf(button(unknown, reply("a", "A")), PNG)).containsEntry("interactive.header.image.id", "unknown media");
        Header wrongKind = new Header("video", null, null, new MediaRef("img1", null), null);
        assertThat(fieldsOf(button(wrongKind, reply("a", "A")), PNG))
                .containsEntry("interactive.header.video.id", "must be an MP4 or 3GPP video");
        Header ok = new Header("image", null, new MediaRef("img1", null), null, null);
        assertThat(fieldsOf(button(ok, reply("a", "A")), PNG)).isEmpty();
    }

    @Test
    void headerTypeMustBeKnownAndMatchItsField() {
        assertThat(fieldsOf(button(new Header("audio", null, null, null, null), reply("a", "A"))))
                .containsEntry("interactive.header.type", "must be text, image, video or document");
        Header mismatch = new Header("text", "Hi", new MediaRef("img1", null), null, null);
        assertThat(fieldsOf(button(mismatch, reply("a", "A")), PNG)).containsEntry("interactive.header.image", "must be absent");
    }

    // ---- button ----

    @Test
    void buttonsNeedOneToThreeReplies() {
        assertThat(fieldsOf(button())).containsEntry("interactive.action.buttons", "needs 1 to 3 buttons");
        assertThat(fieldsOf(button(reply("a", "A"), reply("b", "B"), reply("c", "C"), reply("d", "D"))))
                .containsEntry("interactive.action.buttons", "needs 1 to 3 buttons");
    }

    @Test
    void buttonTypeIsReply() {
        assertThat(fieldsOf(button(new ActionButton("url", new Reply("a", "A")))))
                .containsEntry("interactive.action.buttons.0.type", "must be reply");
    }

    @Test
    void buttonIdAndTitleLimits() {
        assertThat(fieldsOf(button(reply("", "A")))).containsEntry("interactive.action.buttons.0.reply.id", "is required");
        assertThat(fieldsOf(button(reply("i".repeat(257), "A")))).containsEntry("interactive.action.buttons.0.reply.id", "must be at most 256 characters");
        assertThat(fieldsOf(button(reply("a", "")))).containsEntry("interactive.action.buttons.0.reply.title", "is required");
        assertThat(fieldsOf(button(reply("a", "t".repeat(21))))).containsEntry("interactive.action.buttons.0.reply.title", "must be at most 20 characters");
    }

    @Test
    void rejectsDuplicateButtonIdsAndTitlesIgnoringCase() {
        assertThat(fieldsOf(button(reply("a", "Yes"), reply("a", "No")))).containsEntry("interactive.action.buttons.1.reply.id", "must be unique");
        assertThat(fieldsOf(button(reply("a", "Yes"), reply("b", "yes")))).containsEntry("interactive.action.buttons.1.reply.title", "must be unique");
    }

    @Test
    void buttonMessageAllowsNoListOrCtaFields() {
        CloudMessage m = interactive(new Interactive("button", null, new Body("Hi"), null,
                new Action(List.of(reply("a", "A")), "Go", null, null, null)));
        assertThat(fieldsOf(m)).containsEntry("interactive.action.button", "must be absent");
    }

    // ---- list ----

    @Test
    void listNeedsButtonLabelAndSections() {
        CloudMessage noLabel = interactive(new Interactive("list", null, new Body("Pick"), null,
                new Action(null, "", List.of(section("S", row("r", "R", null))), null, null)));
        assertThat(fieldsOf(noLabel)).containsEntry("interactive.action.button", "is required");
        CloudMessage longLabel = interactive(new Interactive("list", null, new Body("Pick"), null,
                new Action(null, "l".repeat(21), List.of(section("S", row("r", "R", null))), null, null)));
        assertThat(fieldsOf(longLabel)).containsEntry("interactive.action.button", "must be at most 20 characters");
        assertThat(fieldsOf(list())).containsEntry("interactive.action.sections", "needs 1 to 10 sections");
    }

    @Test
    void listSectionAndRowLimits() {
        assertThat(fieldsOf(list(section("s".repeat(25), row("r", "R", null)))))
                .containsEntry("interactive.action.sections.0.title", "must be at most 24 characters");
        assertThat(fieldsOf(list(section("S")))).containsEntry("interactive.action.sections.0.rows", "needs at least 1 row");
        assertThat(fieldsOf(list(section("S", row("", "R", null))))).containsEntry("interactive.action.sections.0.rows.0.id", "is required");
        assertThat(fieldsOf(list(section("S", row("r", "t".repeat(25), null))))).containsEntry("interactive.action.sections.0.rows.0.title", "must be at most 24 characters");
        assertThat(fieldsOf(list(section("S", row("r", "R", "d".repeat(73)))))).containsEntry("interactive.action.sections.0.rows.0.description", "must be at most 72 characters");
    }

    @Test
    void listCapsRowsAcrossSections() {
        Row[] six = new Row[6];
        for (int i = 0; i < 6; i++) six[i] = row("a" + i, "A" + i, null);
        Row[] five = new Row[5];
        for (int i = 0; i < 5; i++) five[i] = row("b" + i, "B" + i, null);
        assertThat(fieldsOf(list(section("A", six), section("B", five))))
                .containsEntry("interactive.action.sections", "at most 10 rows in total");
    }

    @Test
    void listRowsMustBeUniqueAcrossSections() {
        assertThat(fieldsOf(list(section("A", row("x", "One", null)), section("B", row("x", "Two", null)))))
                .containsEntry("interactive.action.sections.1.rows.0.id", "must be unique");
        assertThat(fieldsOf(list(section("A", row("x", "One", null)), section("B", row("y", "ONE", null)))))
                .containsEntry("interactive.action.sections.1.rows.0.title", "must be unique");
    }

    @Test
    void listHeaderIsTextOnly() {
        CloudMessage m = interactive(new Interactive("list", new Header("image", null, new MediaRef("img1", null), null, null),
                new Body("Pick"), null, new Action(null, "Go", List.of(section("S", row("r", "R", null))), null, null)));
        assertThat(fieldsOf(m, PNG)).containsEntry("interactive.header.type", "must be text");
    }

    // ---- cta_url ----

    @Test
    void ctaNeedsNameTextAndHttpsUrl() {
        assertThat(fieldsOf(cta("", "https://x"))).containsEntry("interactive.action.parameters.display_text", "is required");
        assertThat(fieldsOf(cta("t".repeat(21), "https://x"))).containsEntry("interactive.action.parameters.display_text", "must be at most 20 characters");
        assertThat(fieldsOf(cta("See", "http://x"))).containsEntry("interactive.action.parameters.url", "must start with https://");
        assertThat(fieldsOf(cta("See", "https://" + "x".repeat(1993)))).containsEntry("interactive.action.parameters.url", "must be at most 2000 characters");
        CloudMessage wrongName = interactive(new Interactive("cta_url", null, new Body("See"), null,
                new Action(null, null, null, "flow", new CtaParameters("See", "https://x"))));
        assertThat(fieldsOf(wrongName)).containsEntry("interactive.action.name", "must be cta_url");
        assertThat(fieldsOf(cta("See", "https://x"))).isEmpty();
    }

    // ---- location request ----

    @Test
    void locationRequestIsBodyAndSendLocationOnly() {
        assertThat(fieldsOf(location("Where?"))).isEmpty();
        CloudMessage withHeader = interactive(new Interactive("location_request_message",
                new Header("text", "Hi", null, null, null), new Body("Where?"), null,
                new Action(null, null, null, "send_location", null)));
        assertThat(fieldsOf(withHeader)).containsEntry("interactive.header", "must be absent");
        CloudMessage wrongName = interactive(new Interactive("location_request_message", null, new Body("Where?"), null,
                new Action(null, null, null, "cta_url", null)));
        assertThat(fieldsOf(wrongName)).containsEntry("interactive.action.name", "must be send_location");
    }

    // ---- replies helper ----

    @Test
    void repliesOfAButtonMessageAreItsButtons() {
        assertThat(CloudMessageValidator.replies(button(reply("a", "Yes"), reply("b", "No")).interactive()))
                .extracting(Reply::title).containsExactly("Yes", "No");
    }

    @Test
    void repliesOfAListAreItsRows() {
        assertThat(CloudMessageValidator.replies(list(section("S", row("r1", "One", null), row("r2", "Two", null))).interactive()))
                .extracting(Reply::id).containsExactly("r1", "r2");
    }

    @Test
    void repliesOfOtherKindsAreEmpty() {
        assertThat(CloudMessageValidator.replies(cta("See", "https://x").interactive())).isEmpty();
        assertThat(CloudMessageValidator.replies(location("Where?").interactive())).isEmpty();
    }

    @Test
    void mediaTypesAreKnown() {
        assertThat(CloudMediaTypes.forHeader("image")).contains("image/png", "image/jpeg");
        assertThat(CloudMediaTypes.forHeader("video")).contains("video/mp4");
        assertThat(CloudMediaTypes.forHeader("document")).contains("application/pdf");
        assertThat(CloudMediaTypes.forHeader("text")).isEmpty();
        assertThat(CloudMediaTypes.maxBytes("image")).isEqualTo(5L * 1024 * 1024);
        assertThat(CloudMediaTypes.maxBytes("video")).isEqualTo(16L * 1024 * 1024);
        assertThat(CloudMediaTypes.maxBytes("document")).isEqualTo(16L * 1024 * 1024);
    }
}
