package me.nageshk.omnichannel.service.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import me.nageshk.omnichannel.common.api.ValidationException;
import org.junit.jupiter.api.Test;

/** Spec 2026-11-18 link addresses §5.1: what a person types becomes a clean address, or a message saying why not. */
class PagesUrlTest {

    private static String refusal(String text, boolean insecure) {
        try {
            PagesUrl.parse(text, insecure);
        } catch (ValidationException e) {
            return e.fields().get("baseUrl");
        }
        throw new AssertionError("accepted " + text);
    }

    @Test
    void aSubdomainIsLowerCasedWithoutItsTrailingSlash() {
        PagesUrl u = PagesUrl.parse("https://User-Task.BimalInstitute.com/", false);
        assertThat(u.baseUrl()).isEqualTo("https://user-task.bimalinstitute.com");
        assertThat(u.host()).isEqualTo("user-task.bimalinstitute.com");
        assertThat(u.path()).isEmpty();
    }

    @Test
    void aFolderIsKeptAndSpacesAreTrimmed() {
        PagesUrl u = PagesUrl.parse(" https://bimalinstitute.com/wa/ ", false);
        assertThat(u.baseUrl()).isEqualTo("https://bimalinstitute.com/wa");
        assertThat(u.host()).isEqualTo("bimalinstitute.com");
        assertThat(u.path()).isEqualTo("/wa");
        assertThat(PagesUrl.parse("https://x.com/a/b-c/d_e", false).path()).isEqualTo("/a/b-c/d_e");
    }

    @Test
    void aTrailingDotOnTheHostIsDropped() {
        PagesUrl u = PagesUrl.parse("https://links.acme.in./wa", false);
        assertThat(u.host()).isEqualTo("links.acme.in");
        assertThat(u.baseUrl()).isEqualTo("https://links.acme.in/wa");
    }

    @Test
    void blankIsRefused() {
        assertThat(refusal("  ", false)).isEqualTo("Enter an address");
        assertThat(refusal(null, false)).isEqualTo("Enter an address");
    }

    @Test
    void aMissingOrOtherSchemeIsRefused() {
        assertThat(refusal("bimalinstitute.com", false)).isEqualTo("Start the address with https://");
        assertThat(refusal("ftp://bimalinstitute.com", false)).isEqualTo("Start the address with https://");
    }

    @Test
    void plainHttpIsRefusedUnlessInsecureIsAllowed() {
        assertThat(refusal("http://x.com", false)).isEqualTo("Use https://");
        assertThat(PagesUrl.parse("http://x.com", true).baseUrl()).isEqualTo("http://x.com");
    }

    @Test
    void anIpAddressIsRefused() {
        assertThat(refusal("https://203.0.113.5", false)).isEqualTo("Use a domain name, not an IP address");
        assertThat(refusal("https://[::1]", false)).isEqualTo("Use a domain name, not an IP address");
    }

    @Test
    void aPortIsRefusedUnlessInsecureIsAllowed() {
        assertThat(refusal("https://x.com:8443", false)).isEqualTo("Leave out the port");
        PagesUrl local = PagesUrl.parse("http://localhost:8089/a", true);
        assertThat(local.host()).isEqualTo("localhost");
        assertThat(local.baseUrl()).isEqualTo("http://localhost:8089/a");
    }

    @Test
    void queriesFragmentsAndUserInfoAreRefused() {
        String only = "Only a domain and an optional folder, e.g. https://example.com/wa";
        assertThat(refusal("https://x.com/?a=1", false)).isEqualTo(only);
        assertThat(refusal("https://x.com/#top", false)).isEqualTo(only);
        assertThat(refusal("https://me@x.com", false)).isEqualTo(only);
    }

    @Test
    void aFolderWithOddCharactersOrTooManyPartsIsRefused() {
        String folder = "The folder may have up to 3 parts of letters, digits, - and _";
        assertThat(refusal("https://x.com/a.b", false)).isEqualTo(folder);
        assertThat(refusal("https://x.com/a%20b", false)).isEqualTo(folder);
        assertThat(refusal("https://x.com/a/b/c/d", false)).isEqualTo(folder);
        assertThat(refusal("https://x.com//a", false)).isEqualTo(folder);
    }

    @Test
    void aNonAsciiHostIsRefused() {
        assertThat(refusal("https://bimalinstitüte.com", false)).isEqualTo("Use the ASCII (xn--) form of the name");
    }

    @Test
    void aHostWithoutADotIsRefusedUnlessInsecure() {
        assertThat(refusal("https://intranet", false)).isEqualTo("Use a full domain name");
    }
}
