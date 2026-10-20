package me.nageshk.omnichannel.contact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AttributeKeysTest {

    @Test
    void lowercasesAndJoinsWithUnderscores() {
        assertThat(AttributeKeys.generate("Preferred size", Set.of())).isEqualTo("preferred_size");
    }

    @Test
    void trimsSurroundingSeparators() {
        assertThat(AttributeKeys.generate("  City ", Set.of())).isEqualTo("city");
    }

    @Test
    void prefixesWhenNotStartingWithLetter() {
        assertThat(AttributeKeys.generate("2nd phone", Set.of())).isEqualTo("a_2nd_phone");
    }

    @Test
    void nonAsciiRunsBecomeSeparators() {
        assertThat(AttributeKeys.generate("Ünïcode!", Set.of())).isEqualTo("n_code");
    }

    @Test
    void emptyOrSymbolsOnlyGivesAttribute() {
        assertThat(AttributeKeys.generate("", Set.of())).isEqualTo("attribute");
        assertThat(AttributeKeys.generate("  ", Set.of())).isEqualTo("attribute");
        assertThat(AttributeKeys.generate("!!! ???", Set.of())).isEqualTo("attribute");
        assertThat(AttributeKeys.generate(null, Set.of())).isEqualTo("attribute");
    }

    @Test
    void clashGetsNumericSuffix() {
        assertThat(AttributeKeys.generate("City", Set.of("city"))).isEqualTo("city_2");
        assertThat(AttributeKeys.generate("City", Set.of("city", "city_2"))).isEqualTo("city_3");
    }

    @Test
    void thisIsReservedSoItIsTreatedAsTaken() {
        assertThat(AttributeKeys.generate("This", Set.of())).isEqualTo("this_2");
        assertThat(AttributeKeys.generate("this", Set.of("this_2"))).isEqualTo("this_3");
    }

    @Test
    void longNameIsCutTo40() {
        String key = AttributeKeys.generate("a".repeat(60), Set.of());
        assertThat(key).isEqualTo("a".repeat(40));
    }

    @Test
    void cutNameWithClashStillTotals40OrLess() {
        String base = "a".repeat(40);
        String key = AttributeKeys.generate("a".repeat(60), Set.of(base));
        assertThat(key).hasSize(40).isEqualTo("a".repeat(38) + "_2");
        String third = AttributeKeys.generate("a".repeat(60), Set.of(base, key));
        assertThat(third).hasSize(40).isEqualTo("a".repeat(38) + "_3");
    }
}
