package me.nageshk.omnichannel.app.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The test server holds its port on 127.0.0.1, where the tests call it. Bound on every address instead, macOS lets
 * another program hold the same port on 127.0.0.1 alone (VS Code's helper did, on 2026-11-02), and the tests' calls to
 * localhost reach that program: every request of a whole test class came back 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestServerAddressIT {

    @LocalServerPort int port;

    @Test
    void noOtherProgramCanTakeTheTestServersPortOnLoopback() {
        assertThatThrownBy(() -> {
            try (ServerSocket other = new ServerSocket()) {
                other.setReuseAddress(true);
                other.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            }
        }).isInstanceOf(BindException.class);
    }
}
