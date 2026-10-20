package me.nageshk.omnichannel.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlaceholderParserTest {

    private static void rejects(String text, String message) {
        assertThatThrownBy(() -> PlaceholderParser.parse(text))
                .isInstanceOf(PlaceholderSyntaxException.class)
                .hasMessage(message);
    }

    @Test
    void parsesAllKinds() {
        assertThat(PlaceholderParser.parse("Hi {{contact.name}} {{contact.phone}} {{contact.attributes.city}} {{order_id}}"))
                .containsExactly(
                        new PlaceholderRef("contact.name", PlaceholderKind.CONTACT_FIELD, null),
                        new PlaceholderRef("contact.phone", PlaceholderKind.CONTACT_FIELD, null),
                        new PlaceholderRef("contact.attributes.city", PlaceholderKind.CONTACT_ATTRIBUTE, "city"),
                        new PlaceholderRef("order_id", PlaceholderKind.AGENT, null));
    }

    @Test
    void normalisesWhitespace() {
        assertThat(PlaceholderParser.parse("{{  x  }}")).containsExactly(
                new PlaceholderRef("x", PlaceholderKind.AGENT, null));
        assertThat(PlaceholderParser.normalise("a {{  x  }} b {{ contact.name}} {{y }}"))
                .isEqualTo("a {{x}} b {{contact.name}} {{y}}");
    }

    @Test
    void distinctInOrder() {
        List<PlaceholderRef> refs = PlaceholderParser.parse("{{b}} {{a}} {{ b }} {{a}} {{c}}");
        assertThat(refs).extracting(PlaceholderRef::name).containsExactly("b", "a", "c");
    }

    @Test
    void rejectsSection() {
        rejects("{{#if}}x{{/if}}", "\"{{#\" is not allowed — only {{name}} placeholders are supported");
    }

    @Test
    void rejectsInverted() {
        rejects("{{^x}}", "\"{{^\" is not allowed — only {{name}} placeholders are supported");
    }

    @Test
    void rejectsPartial() {
        rejects("{{> part}}", "\"{{>\" is not allowed — only {{name}} placeholders are supported");
    }

    @Test
    void rejectsComment() {
        rejects("{{! note }}", "\"{{!\" is not allowed — only {{name}} placeholders are supported");
    }

    @Test
    void rejectsTripleBrace() {
        rejects("{{{x}}}", "\"{{{\" is not allowed — only {{name}} placeholders are supported");
    }

    @Test
    void rejectsAmpersand() {
        rejects("{{& x}}", "\"{{&\" is not allowed — only {{name}} placeholders are supported");
    }

    @Test
    void rejectsDelimiterChange() {
        rejects("{{=<% %>=}}", "\"{{=\" is not allowed — only {{name}} placeholders are supported");
    }

    @Test
    void rejectsSigilAfterSpaces() {
        rejects("{{ # x}}", "\"{{#\" is not allowed — only {{name}} placeholders are supported");
    }

    @Test
    void rejectsUnclosed() {
        rejects("Hello {{name", "unclosed \"{{\"");
        rejects("Hello {{name}", "unclosed \"{{\"");
    }

    @Test
    void rejectsUppercaseName() {
        rejects("{{Name}}", "invalid placeholder \"{{Name}}\"");
    }

    @Test
    void rejectsDashName() {
        rejects("{{order-id}}", "invalid placeholder \"{{order-id}}\"");
        rejects("{{}}", "invalid placeholder \"{{}}\"");
        rejects("{{a b}}", "invalid placeholder \"{{a b}}\"");
    }

    @Test
    void rejectsReservedContact() {
        rejects("{{contact}}", "\"{{contact}}\" is not a contact field");
        rejects("{{contact.x}}", "\"{{contact.x}}\" is not a contact field");
        rejects("{{contact.attributes}}", "\"{{contact.attributes}}\" is not a contact field");
    }

    @Test
    void rejectsReservedThis() {
        rejects("{{this}}", "\"{{this}}\" is reserved");
        rejects("{{ this }}", "\"{{this}}\" is reserved");
        rejects("{{contact.attributes.this}}", "\"{{contact.attributes.this}}\" is reserved");
        assertThat(PlaceholderParser.parse("{{this_2}} {{thistle}} {{contact.attributes.this_2}}")).hasSize(3);
    }

    @Test
    void rejectsLongName() {
        String ok = "a".repeat(40);
        assertThat(PlaceholderParser.parse("{{" + ok + "}}")).hasSize(1);
        String bad = "a".repeat(41);
        rejects("{{" + bad + "}}", "invalid placeholder \"{{" + bad + "}}\"");
        rejects("{{contact.attributes." + bad + "}}", "invalid placeholder \"{{contact.attributes." + bad + "}}\"");
    }

    @Test
    void plainTextHasNoRefs() {
        assertThat(PlaceholderParser.parse("Hello world")).isEmpty();
        assertThat(PlaceholderParser.parse("")).isEmpty();
        assertThat(PlaceholderParser.parse(null)).isEmpty();
    }

    @Test
    void singleBraceIsText() {
        assertThat(PlaceholderParser.parse("a {x} b")).isEmpty();
        assertThat(PlaceholderParser.normalise("a {x} b")).isEqualTo("a {x} b");
    }
}
