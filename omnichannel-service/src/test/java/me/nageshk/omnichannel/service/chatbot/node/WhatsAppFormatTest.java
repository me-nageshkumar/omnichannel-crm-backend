package me.nageshk.omnichannel.service.chatbot.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Spec §5.9: the AI's markdown as WhatsApp formatting, cut into parts WhatsApp takes (4096 characters). */
class WhatsAppFormatTest {

    @Test
    void boldBecomesSingleStars() {
        assertThat(WhatsAppFormat.convert("**b**")).containsExactly("*b*");
        assertThat(WhatsAppFormat.convert("Hello **Veenu**, welcome")).containsExactly("Hello *Veenu*, welcome");
    }

    @Test
    void italicBecomesUnderscores() {
        assertThat(WhatsAppFormat.convert("__i__")).containsExactly("_i_");
        assertThat(WhatsAppFormat.convert("*i*")).containsExactly("_i_");
        assertThat(WhatsAppFormat.convert("a **bold** and *slanted* word")).containsExactly("a *bold* and _slanted_ word");
    }

    @Test
    void headingsBecomeBold() {
        assertThat(WhatsAppFormat.convert("# H")).containsExactly("*H*");
        assertThat(WhatsAppFormat.convert("## Our plans\nPick one")).containsExactly("*Our plans*\nPick one");
        assertThat(WhatsAppFormat.convert("### **Already** bold")).containsExactly("*Already bold*");
    }

    @Test
    void linksShowTheirAddress() {
        assertThat(WhatsAppFormat.convert("[t](u)")).containsExactly("t (u)");
        assertThat(WhatsAppFormat.convert("See [our shop](https://example.com/shop) today"))
                .containsExactly("See our shop (https://example.com/shop) today");
    }

    @Test
    void strikeThroughAndBullets() {
        assertThat(WhatsAppFormat.convert("~~old~~ new")).containsExactly("~old~ new");
        assertThat(WhatsAppFormat.convert("* one\n* two\n- three")).containsExactly("- one\n- two\n- three");
    }

    @Test
    void codeIsKept() {
        String fenced = "Run this:\n```\nx = **y** * 2\n```";
        assertThat(WhatsAppFormat.convert(fenced)).containsExactly(fenced);
        assertThat(WhatsAppFormat.convert("use `a*b*c` here")).containsExactly("use `a*b*c` here");
    }

    @Test
    void plainTextUntouchedAndBlankIsNothing() {
        assertThat(WhatsAppFormat.convert("2 * 3 = 6, snake_case_name")).containsExactly("2 * 3 = 6, snake_case_name");
        assertThat(WhatsAppFormat.convert(null)).isEmpty();
        assertThat(WhatsAppFormat.convert("  \n ")).isEmpty();
    }

    @Test
    void longTextSplitsAtAParagraph() {
        String first = "a".repeat(2999) + ".";
        String second = "b".repeat(1998) + ".";
        List<String> parts = WhatsAppFormat.convert(first + "\n\n" + second);
        assertThat(parts).containsExactly(first, second);
    }

    @Test
    void longParagraphSplitsAtASentence() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 5000) sb.append("This is one sentence of the long answer. ");
        String text = sb.toString().strip();
        List<String> parts = WhatsAppFormat.convert(text);
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).hasSizeLessThanOrEqualTo(WhatsAppFormat.MAX_PART).endsWith(".");
        assertThat(parts.get(1)).startsWith("This is");
        assertThat(String.join(" ", parts)).isEqualTo(text);
    }

    @Test
    void textWithNoBreakIsCutHard() {
        List<String> parts = WhatsAppFormat.convert("x".repeat(9000));
        assertThat(parts).hasSize(3);
        assertThat(parts.get(0)).hasSize(WhatsAppFormat.MAX_PART);
        assertThat(String.join("", parts)).hasSize(9000);
    }
}
