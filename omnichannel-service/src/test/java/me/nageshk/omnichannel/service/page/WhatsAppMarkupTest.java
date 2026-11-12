package me.nageshk.omnichannel.service.page;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The page shows WhatsApp's four marks the way the console's formatWhatsApp does, after escaping. */
class WhatsAppMarkupTest {

    @Test
    void boldItalicStrikeMono() {
        assertThat(WhatsAppMarkup.toHtml("*a* _b_ ~c~ ```d```"))
                .isEqualTo("<strong>a</strong> <em>b</em> <s>c</s> <code>d</code>");
    }

    @Test
    void escapesBeforeFormatting() {
        assertThat(WhatsAppMarkup.toHtml("<script>*x*</script>"))
                .isEqualTo("&lt;script&gt;<strong>x</strong>&lt;/script&gt;");
        assertThat(WhatsAppMarkup.toHtml("\"q\" & 'a'")).isEqualTo("&quot;q&quot; &amp; &#39;a&#39;");
    }

    @Test
    void noNesting() {
        assertThat(WhatsAppMarkup.toHtml("*_a_*")).isEqualTo("<strong>_a_</strong>");
    }

    @Test
    void marksNeedNonSpaceEdges() {
        assertThat(WhatsAppMarkup.toHtml("* a*")).isEqualTo("* a*");
        assertThat(WhatsAppMarkup.toHtml("a * b")).isEqualTo("a * b");
    }

    @Test
    void newlines() {
        assertThat(WhatsAppMarkup.toHtml("a\nb")).isEqualTo("a<br>b");
    }

    @Test
    void plainStripsMarks() {
        assertThat(WhatsAppMarkup.plain("*a* _b_ ~c~ ```d```\ne")).isEqualTo("a b c d e");
    }

    @Test
    void nullIsEmpty() {
        assertThat(WhatsAppMarkup.toHtml(null)).isEmpty();
        assertThat(WhatsAppMarkup.plain(null)).isEmpty();
    }
}
