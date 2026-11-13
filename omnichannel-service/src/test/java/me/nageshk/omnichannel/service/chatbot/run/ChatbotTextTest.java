package me.nageshk.omnichannel.service.chatbot.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Spec §4.4: lenient Mustache over the run's variables; plain for WhatsApp, JSON-escaped for request bodies. */
class ChatbotTextTest {

    private final Map<String, Object> vars = Map.of("contact", Map.of("name", "Veenu"),
            "AskBudget", Map.of("answer", 5000), "Q", Map.of("answer", "He said \"hi\"\nok"));

    @Test
    void rendersNestedPaths() {
        assertThat(ChatbotText.render("Hi {{contact.name}}, {{AskBudget.answer}}", vars)).isEqualTo("Hi Veenu, 5000");
    }

    @Test
    void unknownPathIsEmpty() {
        assertThat(ChatbotText.render("Hi {{Nope.answer}}!", vars)).isEqualTo("Hi !");
        assertThat(ChatbotText.render("Hi {{contact.missing}}!", vars)).isEqualTo("Hi !");
    }

    @Test
    void plainTextIsNotHtmlEscaped() {
        assertThat(ChatbotText.render("{{Q.answer}} <b>", vars)).startsWith("He said \"hi\"\nok <b>");
    }

    @Test
    void jsonEscapesValues() throws Exception {
        String json = ChatbotText.renderJson("{\"a\":\"{{Q.answer}}\",\"n\":{{AskBudget.answer}}}", vars);
        var node = JsonMapper.builder().build().readTree(json);
        assertThat(node.get("a").asString()).isEqualTo("He said \"hi\"\nok");
        assertThat(node.get("n").asInt()).isEqualTo(5000);
    }

    @Test
    void pathsListsReferences() {
        assertThat(ChatbotText.paths("{{contact.name}} and {{ AskBudget.answer }} and {{#x}}{{/x}}"))
                .containsExactlyInAnyOrder("contact.name", "AskBudget.answer");
    }

    @Test
    void renameNodeRewritesPrefixOnly() {
        assertThat(ChatbotText.renameNode("{{Old.answer}} {{Older.x}} {{ Old.b }}", "Old", "New"))
                .isEqualTo("{{New.answer}} {{Older.x}} {{ New.b }}");
    }

    @Test
    void nullTemplateIsEmpty() {
        assertThat(ChatbotText.render(null, vars)).isEmpty();
    }
}
