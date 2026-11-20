package me.nageshk.omnichannel.meta.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Template;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TemplateVariablesTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private JsonNode json(String s) {
        return mapper.readTree(s);
    }

    private static final String POSITIONAL = "[{\"type\":\"BODY\",\"text\":\"Hi {{1}}, your {{2}} starts soon\","
            + "\"example\":{\"body_text\":[[\"Veenu\",\"BCA\"]]}},{\"type\":\"FOOTER\",\"text\":\"Bimal Institute\"}]";

    @Test
    void positionalBody() {
        TemplateVariables.Derived d = TemplateVariables.derive(json(POSITIONAL), "POSITIONAL", "UTILITY");
        assertThat(d.variables()).extracting(TemplateVariables.Variable::key).containsExactly("body.1", "body.2");
        assertThat(d.variables().get(0).example()).isEqualTo("Veenu");
        assertThat(d.variables().get(0).component()).isEqualTo("BODY");
        assertThat(d.headerFormat()).isEqualTo("NONE");
        assertThat(d.supported()).isTrue();
    }

    @Test
    void namedBody() {
        TemplateVariables.Derived d = TemplateVariables.derive(json("[{\"type\":\"BODY\",\"text\":\"Hi {{first_name}}\","
                + "\"example\":{\"body_text_named_params\":[{\"param_name\":\"first_name\",\"example\":\"Asha\"}]}}]"),
                "NAMED", "MARKETING");
        assertThat(d.variables()).extracting(TemplateVariables.Variable::key).containsExactly("body.first_name");
        assertThat(d.variables().get(0).example()).isEqualTo("Asha");
    }

    @Test
    void headerTextAndUrlButton() {
        TemplateVariables.Derived d = TemplateVariables.derive(json("[{\"type\":\"HEADER\",\"format\":\"TEXT\","
                + "\"text\":\"Hello {{1}}\",\"example\":{\"header_text\":[\"Veenu\"]}},{\"type\":\"BODY\",\"text\":\"x\"},"
                + "{\"type\":\"BUTTONS\",\"buttons\":[{\"type\":\"URL\",\"text\":\"Apply\",\"url\":\"https://x.in/{{1}}\","
                + "\"example\":[\"https://x.in/abc\"]},{\"type\":\"QUICK_REPLY\",\"text\":\"Call me\"}]}]"),
                "POSITIONAL", "UTILITY");
        assertThat(d.variables()).extracting(TemplateVariables.Variable::key).containsExactly("header.1", "button.0");
        assertThat(d.headerFormat()).isEqualTo("TEXT");
    }

    @Test
    void headerImageHasNoTextVariable() {
        TemplateVariables.Derived d = TemplateVariables.derive(json("[{\"type\":\"HEADER\",\"format\":\"IMAGE\"},"
                + "{\"type\":\"BODY\",\"text\":\"Brochure\"}]"), "POSITIONAL", "MARKETING");
        assertThat(d.headerFormat()).isEqualTo("IMAGE");
        assertThat(d.variables()).isEmpty();
    }

    @Test
    void unsupportedKinds() {
        for (String c : List.of("[{\"type\":\"CAROUSEL\",\"cards\":[]}]", "[{\"type\":\"LIMITED_TIME_OFFER\"}]",
                "[{\"type\":\"BUTTONS\",\"buttons\":[{\"type\":\"FLOW\",\"text\":\"x\"}]}]",
                "[{\"type\":\"BUTTONS\",\"buttons\":[{\"type\":\"CATALOG\",\"text\":\"x\"}]}]",
                "[{\"type\":\"HEADER\",\"format\":\"LOCATION\"}]")) {
            TemplateVariables.Derived d = TemplateVariables.derive(json(c), "POSITIONAL", "MARKETING");
            assertThat(d.supported()).as(c).isFalse();
            assertThat(d.unsupportedReason()).as(c).isNotBlank();
        }
        assertThat(TemplateVariables.derive(json("[{\"type\":\"BODY\",\"text\":\"{{1}} is your code\"}]"),
                "POSITIONAL", "AUTHENTICATION").supported()).isFalse();
    }

    @Test
    void buildFillsComponentsInOrder() {
        JsonNode components = json("[{\"type\":\"HEADER\",\"format\":\"IMAGE\"},{\"type\":\"BODY\",\"text\":"
                + "\"Hi {{1}}, your {{2}} starts soon\"},{\"type\":\"BUTTONS\",\"buttons\":[{\"type\":\"URL\","
                + "\"text\":\"Apply\",\"url\":\"https://x.in/{{1}}\"},{\"type\":\"COPY_CODE\",\"example\":\"SAVE10\"}]}]");

        TemplateVariables.Built b = TemplateVariables.build("course_follow_up", "en", components, "POSITIONAL",
                Map.of("body.1", "Veenu", "body.2", "BCA", "button.0", "abc", "button.1", "BIMAL10"), "meta-media-9");

        Template t = b.template();
        assertThat(t.name()).isEqualTo("course_follow_up");
        assertThat(t.language().code()).isEqualTo("en");
        assertThat(t.components()).hasSize(4);
        assertThat(t.components().get(0).type()).isEqualTo("header");
        assertThat(t.components().get(0).parameters().get(0).image().id()).isEqualTo("meta-media-9");
        assertThat(t.components().get(1).parameters()).extracting(Template.Parameter::text).containsExactly("Veenu", "BCA");
        assertThat(t.components().get(2).subType()).isEqualTo("url");
        assertThat(t.components().get(2).index()).isEqualTo("0");
        assertThat(t.components().get(3).subType()).isEqualTo("copy_code");
        assertThat(t.components().get(3).parameters().get(0).couponCode()).isEqualTo("BIMAL10");
        assertThat(b.renderedBody()).isEqualTo("Hi Veenu, your BCA starts soon");
        assertThat(b.snapshot().header().format()).isEqualTo("IMAGE");
        assertThat(b.snapshot().buttons()).hasSize(2);
    }

    @Test
    void namedParametersCarryParameterName() {
        TemplateVariables.Built b = TemplateVariables.build("hi", "en_US", json("[{\"type\":\"BODY\","
                + "\"text\":\"Hi {{first_name}}\"}]"), "NAMED", Map.of("body.first_name", "Asha"), null);
        Template.Parameter p = b.template().components().get(0).parameters().get(0);
        assertThat(p.parameterName()).isEqualTo("first_name");
        assertThat(p.text()).isEqualTo("Asha");
    }

    @Test
    void missingValuesAreNamed() {
        assertThat(TemplateVariables.missing(TemplateVariables.derive(json(POSITIONAL), "POSITIONAL", "UTILITY"),
                Map.of("body.1", "Veenu", "body.2", " "))).containsExactly("body.2");
    }
}
