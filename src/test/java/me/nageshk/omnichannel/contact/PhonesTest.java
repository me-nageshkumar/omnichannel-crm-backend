package me.nageshk.omnichannel.contact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhonesTest {

    @Test
    void normaliseStripsPlusSpacesDashesAndBrackets() {
        assertThat(Phones.normalise("+91 98123-45678")).isEqualTo("919812345678");
        assertThat(Phones.normalise(" (1) 415 555 0100 ")).isEqualTo("14155550100");
    }

    @Test
    void normaliseOfNullIsNull() {
        assertThat(Phones.normalise(null)).isNull();
    }

    @Test
    void validNeedsEightToFifteenDigitsNotStartingWithZero() {
        assertThat(Phones.isValid("919812345678")).isTrue();
        assertThat(Phones.isValid("12345678")).isTrue();
        assertThat(Phones.isValid("123456789012345")).isTrue();
        assertThat(Phones.isValid("1234567")).isFalse();
        assertThat(Phones.isValid("1234567890123456")).isFalse();
        assertThat(Phones.isValid("0123456789")).isFalse();
        assertThat(Phones.isValid("91981234abcd")).isFalse();
        assertThat(Phones.isValid(null)).isFalse();
        assertThat(Phones.isValid("")).isFalse();
    }
}
