package me.nageshk.omnichannel.service.chatbot.form;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.service.chatbot.flow.settings.AskFormSettings;
import me.nageshk.omnichannel.service.chatbot.flow.settings.FormField;
import me.nageshk.omnichannel.service.chatbot.flow.settings.FormFieldType;
import org.junit.jupiter.api.Test;

/** Spec §8.4: server-side validation mirrors the form's HTML5 rules; clean answers are typed. */
class FormAnswersTest {

    private static final AskFormSettings S = new AskFormSettings("T", null, List.of(
            new FormField("name", "Name", FormFieldType.TEXT, true, null),
            new FormField("budget", "Budget", FormFieldType.NUMBER, false, null),
            new FormField("email", "Email", FormFieldType.EMAIL, false, null),
            new FormField("phone", "Phone", FormFieldType.PHONE, false, null),
            new FormField("when", "When", FormFieldType.DATE, false, null),
            new FormField("size", "Size", FormFieldType.CHOICE, false, List.of("S", "M"))), null, null, "{{form.url}}");

    @Test
    void requiredMissing() {
        assertThat(FormAnswers.validate(S, Map.of("name", "  "))).containsEntry("name", "is required");
        assertThat(FormAnswers.validate(S, Map.of("name", "Veenu"))).isEmpty();
    }

    @Test
    void numberParses() {
        assertThat(FormAnswers.validate(S, Map.of("name", "V", "budget", "5,000"))).isEmpty();
        assertThat(FormAnswers.validate(S, Map.of("name", "V", "budget", "five"))).containsKey("budget");
    }

    @Test
    void choiceMustBeAnOption() {
        assertThat(FormAnswers.validate(S, Map.of("name", "V", "size", "XL"))).containsKey("size");
        assertThat(FormAnswers.validate(S, Map.of("name", "V", "size", "M"))).isEmpty();
    }

    @Test
    void dateEmailPhoneFormats() {
        assertThat(FormAnswers.validate(S, Map.of("name", "V", "when", "2026-11-13", "email", "a@b.co", "phone", "+91 9799750743"))).isEmpty();
        assertThat(FormAnswers.validate(S, Map.of("name", "V", "when", "13/11/2026"))).isEmpty();
        assertThat(FormAnswers.validate(S, Map.of("name", "V", "when", "soon"))).containsKey("when");
        assertThat(FormAnswers.validate(S, Map.of("name", "V", "email", "nope"))).containsKey("email");
        assertThat(FormAnswers.validate(S, Map.of("name", "V", "phone", "12"))).containsKey("phone");
    }

    @Test
    void tooLongIsRefused() {
        assertThat(FormAnswers.validate(S, Map.of("name", "x".repeat(1001)))).containsKey("name");
    }

    @Test
    void cleanAnswersTyped() {
        Map<String, Object> clean = FormAnswers.clean(S, Map.of("name", " Veenu ", "budget", "5,000", "when", "13/11/2026",
                "phone", "+91 97997 50743", "size", "M"));
        assertThat(clean).containsEntry("name", "Veenu").containsEntry("budget", new BigDecimal("5000"))
                .containsEntry("when", "2026-11-13").containsEntry("phone", "919799750743").containsEntry("size", "M");
        assertThat(clean).doesNotContainKey("email");
    }
}
