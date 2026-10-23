package me.nageshk.omnichannel.service.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samskivert.mustache.MustacheException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateRendererTest {

    @Test
    void compoundKeyResolvesThroughNestedMaps() {
        Map<String, Object> ctx = Map.of("contact", Map.of("name", "Asha", "attributes", Map.of("city", "Pune")));
        assertThat(TemplateRenderer.COMPILER.compile("{{contact.name}} / {{contact.attributes.city}}").execute(ctx))
                .isEqualTo("Asha / Pune");
    }

    @Test
    void missingVariableThrowsAndNothingIsEscaped() {
        assertThatThrownBy(() -> TemplateRenderer.COMPILER.compile("{{nope}}").execute(Map.of()))
                .isInstanceOf(MustacheException.class);
        assertThatThrownBy(() -> TemplateRenderer.COMPILER.compile("{{contact.attributes.city}}")
                .execute(Map.of("contact", Map.of("attributes", Map.of()))))
                .isInstanceOf(MustacheException.class);
        assertThat(TemplateRenderer.COMPILER.compile("{{v}}").execute(Map.of("v", "<&>\"'"))).isEqualTo("<&>\"'");
    }

    @Test
    void escapeHandlesBackslashCommaSemicolonAndNewlines() {
        String bs = "\\";
        assertThat(TemplateRenderer.escape("a" + bs + "b,c;d\r\ne\nf"))
                .isEqualTo("a" + bs + bs + "b" + bs + ",c" + bs + ";d" + bs + "ne" + bs + "nf");
    }
}
