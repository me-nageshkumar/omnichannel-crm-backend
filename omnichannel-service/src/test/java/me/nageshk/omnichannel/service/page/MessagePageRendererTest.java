package me.nageshk.omnichannel.service.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.junit.jupiter.api.Test;

/** Spec §5.4: the page is inline-only, escaped, carries OG tags, and wires each action the agreed way. */
class MessagePageRendererTest {

    private final MessagePageRenderer r = new MessagePageRenderer();

    static CloudMessage interactive(Interactive i) {
        return new CloudMessage("whatsapp-web", "individual", "919900000002", "interactive", null, i, null);
    }

    static ActionButton reply(String id, String title) {
        return new ActionButton("reply", new Reply(id, title));
    }

    static CloudMessage button(Header header, String body, String footer, ActionButton... buttons) {
        return interactive(new Interactive("button", header, new Body(body), footer == null ? null : new Footer(footer),
                new Action(List.of(buttons), null, null, null, null)));
    }

    static CloudMessage list() {
        return interactive(new Interactive("list", new Header("text", "Delivery Speed", null, null, null),
                new Body("Which delivery option would you like?"), null,
                new Action(null, "View Options", List.of(
                        new Section("Fastest", List.of(new Row("overnight", "Overnight Delivery", "Arrives next morning"))),
                        new Section("Economy", List.of(new Row("standard", "Standard Delivery", null)))), null, null)));
    }

    static CloudMessage cta() {
        return interactive(new Interactive("cta_url", null, new Body("Tap below."), new Footer("Dates may change"),
                new Action(null, null, null, "cta_url", new CtaParameters("See Dates", "https://example.com/dates?x=1&y=2"))));
    }

    static CloudMessage location() {
        return interactive(new Interactive("location_request_message", null, new Body("Where are you?"), null,
                new Action(null, null, null, "send_location", null)));
    }

    PageModel model(CloudMessage m) {
        return new PageModel(m, "tok", "https://pages.test", "919900000001", false);
    }

    String render(CloudMessage m) {
        return r.render(model(m)).html();
    }

    @Test
    void buttonPageHasWaMeLinksAndBeacon() {
        String html = render(button(null, "Hi *there*", "Lucky Shrub", reply("yes", "Yes, book"), reply("no", "No")));
        assertThat(html).contains("href=\"https://wa.me/919900000001?text=Yes%2C+book\"");
        assertThat(html).contains("data-k=\"reply\"").contains("data-id=\"yes\"");
        assertThat(html).contains("https://pages.test/t/tok/e");
        assertThat(html).contains("Hi <strong>there</strong>").contains("Lucky Shrub");
        assertThat(html).contains("+919900000001");
    }

    @Test
    void headerKindsRender() {
        String text = render(button(new Header("text", "Reminder", null, null, null), "b", null, reply("a", "A")));
        assertThat(text).contains("Reminder");
        String image = render(button(new Header("image", null, new MediaRef("img1", null), null, null), "b", null, reply("a", "A")));
        assertThat(image).contains("<img").contains("src=\"https://pages.test/t/tok/m\"");
        String video = render(button(new Header("video", null, null, new MediaRef("v1", null), null), "b", null, reply("a", "A")));
        assertThat(video).contains("<video").contains("https://pages.test/t/tok/m");
        String doc = render(button(new Header("document", null, null, null, new MediaRef("d1", null)), "b", null, reply("a", "A")));
        assertThat(doc).contains("href=\"https://pages.test/t/tok/m\"").doesNotContain("<img");
    }

    @Test
    void listPageHasSheetWithRows() {
        String html = render(list());
        assertThat(html).contains("View Options").contains("Fastest").contains("Economy")
                .contains("Overnight Delivery").contains("Arrives next morning").contains("Standard Delivery")
                .contains("data-id=\"overnight\"").contains("data-id=\"standard\"")
                .contains("href=\"https://wa.me/919900000001?text=Overnight+Delivery\"");
    }

    @Test
    void ctaPageLinksToRedirect() {
        String html = render(cta());
        assertThat(html).contains("href=\"https://pages.test/t/tok/a\"").contains("See Dates").contains("data-k=\"cta\"");
        assertThat(html).doesNotContain("example.com");
    }

    @Test
    void locationPageHasFallback() {
        String html = render(location());
        assertThat(html).contains("data-k=\"location\"")
                .contains("https://wa.me/919900000001?text=Here+is+my+location%3A");
    }

    @Test
    void escapesHtmlEverywhere() {
        String html = render(button(new Header("text", "<i>hdr</i>", null, null, null), "<b>x</b> & y", "<f>",
                reply("q\"1", "\"q\" <t>")));
        assertThat(html).doesNotContain("<b>x</b>").doesNotContain("<i>hdr</i>").doesNotContain("<f>")
                .doesNotContain("<t>");
        assertThat(html).contains("&lt;b&gt;x&lt;/b&gt; &amp; y").contains("&lt;i&gt;hdr&lt;/i&gt;");
        assertThat(html).contains("data-id=\"q&quot;1\"");
        assertThat(html).contains("text=%22q%22+%3Ct%3E");
    }

    @Test
    void ogTagsAreAbsoluteAndCut() {
        String longBody = "word ".repeat(60).trim();
        MessagePageRenderer.Rendered out = r.render(model(button(null, longBody, null, reply("a", "A"))));
        assertThat(out.html()).contains("<meta property=\"og:image\" content=\"https://pages.test/t/tok/og.png\">")
                .contains("<meta property=\"og:url\" content=\"https://pages.test/t/tok\">")
                .contains("<meta property=\"og:type\" content=\"website\">")
                .contains("<meta name=\"robots\" content=\"noindex\">");
        assertThat(out.ogTitle()).hasSize(60);
        assertThat(out.ogDescription()).hasSize(200);
        MessagePageRenderer.Rendered withHeader = r.render(model(button(new Header("text", "Reminder", null, null, null),
                "body", null, reply("a", "A"))));
        assertThat(withHeader.ogTitle()).isEqualTo("Reminder");
        assertThat(withHeader.ogDescription()).isEqualTo("body");
    }

    @Test
    void noExternalReferences() {
        for (CloudMessage m : List.of(button(null, "b", null, reply("a", "A")), list(), cta(), location())) {
            String html = render(m);
            assertThat(html).contains("<style>").contains("<script>");
            Matcher urls = Pattern.compile("(?:src|href)=\"(https?://[^\"]+)\"").matcher(html);
            while (urls.find()) {
                assertThat(urls.group(1)).matches("https://(pages\\.test|wa\\.me)/.*");
            }
            assertThat(html).doesNotContain("<link ").doesNotContain("@import");
        }
    }

    @Test
    void previewHasNoBeaconAndInertLinks() {
        String html = r.render(new PageModel(button(null, "b", null, reply("a", "A")), "preview", "https://pages.test",
                "919900000001", true)).html();
        assertThat(html).doesNotContain("/t/preview/e").doesNotContain("wa.me");
        assertThat(html).contains("data-k=\"reply\" href=\"#\"");
        String ctaHtml = r.render(new PageModel(cta(), "preview", "https://pages.test", "919900000001", true)).html();
        assertThat(ctaHtml).contains("data-k=\"cta\" href=\"#\"");
    }

    @Test
    void expiredAndNotFoundShowNoMessage() {
        assertThat(r.expired()).contains("This message has expired").doesNotContain("wa.me");
        assertThat(r.notFound()).contains("This link is not valid").doesNotContain("wa.me");
    }
}
