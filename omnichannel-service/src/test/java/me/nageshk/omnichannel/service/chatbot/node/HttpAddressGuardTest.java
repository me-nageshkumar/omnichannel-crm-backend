package me.nageshk.omnichannel.service.chatbot.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

/** Spec §6: an HTTP request node never reaches the server's own network. */
class HttpAddressGuardTest {

    private final HttpAddressGuard guard = new HttpAddressGuard(false);

    @Test
    void refusesLoopback() {
        assertThatThrownBy(() -> guard.check(URI.create("http://127.0.0.1:8080/x"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private address");
        assertThatThrownBy(() -> guard.check(URI.create("http://[::1]/x"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesPrivateRanges() {
        for (String host : new String[] {"10.1.2.3", "172.20.0.1", "192.168.1.1", "169.254.1.1", "0.0.0.0", "[fc00::1]", "[fe80::1]"}) {
            assertThatThrownBy(() -> guard.check(URI.create("http://" + host + "/"))).as(host)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void refusesLocalhostNames() {
        for (String host : new String[] {"localhost", "api.localhost", "db.internal", "LOCALHOST"}) {
            assertThatThrownBy(() -> guard.check(URI.create("http://" + host + "/"))).as(host)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void allowsPublicLiteral() {
        assertThatCode(() -> guard.check(URI.create("https://93.184.216.34/"))).doesNotThrowAnyException();
        assertThatCode(() -> guard.check(URI.create("https://[2606:2800:220:1:248:1893:25c8:1946]/"))).doesNotThrowAnyException();
    }

    @Test
    void refusesSchemesAndMissingHost() {
        assertThatThrownBy(() -> guard.check(URI.create("ftp://93.184.216.34/"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.check(URI.create("https:///x"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowPrivateSkipsTheCheck() {
        assertThat(new HttpAddressGuard(true).allowsPrivate()).isTrue();
        assertThatCode(() -> new HttpAddressGuard(true).check(URI.create("http://127.0.0.1:1/x"))).doesNotThrowAnyException();
    }
}
