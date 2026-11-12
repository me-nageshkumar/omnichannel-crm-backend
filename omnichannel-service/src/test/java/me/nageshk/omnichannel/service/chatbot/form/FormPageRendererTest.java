package me.nageshk.omnichannel.service.chatbot.form;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.service.chatbot.flow.settings.AskFormSettings;
import me.nageshk.omnichannel.service.chatbot.flow.settings.FormField;
import me.nageshk.omnichannel.service.chatbot.flow.settings.FormFieldType;
import org.junit.jupiter.api.Test;

/** Spec §8.4: the form page is our own HTML, inline CSS and JS, one input per field, errors re-rendered. */
class FormPageRendererTest {

    private final FormPageRenderer renderer = new FormPageRenderer();

    private static AskFormSettings settings() {
        return new AskFormSettings("Your details", "Two quick <things>", List.of(
                new FormField("name", "Name", FormFieldType.TEXT, true, null),
                new FormField("email", "Email", FormFieldType.EMAIL, false, null),
                new FormField("phone", "Phone", FormFieldType.PHONE, false, null),
                new FormField("budget", "Budget", FormFieldType.NUMBER, true, null),
                new FormField("when", "When", FormFieldType.DATE, false, null),
                new FormField("size", "Size", FormFieldType.CHOICE, true, List.of("S", "M", "L")),
                new FormField("notes", "Notes", FormFieldType.LONG_TEXT, false, null)),
                "Send", "Thanks a lot", "Fill this: {{form.url}}");
    }

    @Test
    void rendersEveryFieldType() {
        String html = renderer.form(settings(), "/f/tok", Map.of(), Map.of());
        assertThat(html).contains("<form method=\"post\" action=\"/f/tok\"")
                .contains("name=\"name\"").contains("required").contains("type=\"email\" ").contains("type=\"tel\" ")
                .contains("type=\"number\"").contains("type=\"date\"")
                .contains("<select name=\"size\"").contains("<option value=\"M\"").contains("<textarea name=\"notes\"")
                .contains(">Send</button>");
    }

    @Test
    void escapesTexts() {
        String html = renderer.form(settings(), "/f/tok", Map.of(), Map.of());
        assertThat(html).contains("Two quick &lt;things&gt;").doesNotContain("<things>");
    }

    @Test
    void noExternalRequests() {
        String html = renderer.form(settings(), "/f/tok", Map.of(), Map.of());
        assertThat(html).contains("<style>").contains("<script>").doesNotContainPattern("(src|href)=\"https?://");
        assertThat(html).contains("<meta name=\"robots\" content=\"noindex\">");
    }

    @Test
    void showsErrorsAndKeepsValues() {
        String html = renderer.form(settings(), "/f/tok", Map.of("name", "Veenu", "size", "L"), Map.of("budget", "is required"));
        assertThat(html).contains("value=\"Veenu\"").contains("<option value=\"L\" selected").contains("is required");
    }

    @Test
    void thanksAndClosed() {
        assertThat(renderer.thanks(settings())).contains("Thanks a lot");
        assertThat(renderer.thanks(new AskFormSettings("T", null, List.of(), null, null, "{{form.url}}"))).contains("Thank you");
        assertThat(renderer.closed()).contains("This form is closed");
    }
}
