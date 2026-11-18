package me.nageshk.omnichannel.service.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The check's fetch has one deadline for headers and body together (link addresses spec §5.4; review finding). */
class HttpPagesProbeTest {

    @Test
    void aBodyThatStallsAfterTheHeadersEndsAtTheDeadline() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread stall = new Thread(() -> {
                try (Socket s = server.accept()) {
                    s.getInputStream().read(new byte[1024]);
                    OutputStream out = s.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nomni".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Thread.sleep(30_000);
                } catch (Exception ignored) {
                    // the test is over
                }
            });
            stall.setDaemon(true);
            stall.start();
            HttpPagesProbe probe = new HttpPagesProbe(true, Duration.ofSeconds(1));

            PagesProbe.Result r = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> probe.get("http://localhost:" + server.getLocalPort() + "/t/-/check/n"));

            assertThat(r.failure()).isEqualTo(PagesProbe.NO_ANSWER);
        }
    }

    @Test
    void aShortAnswerIsRead() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread answer = new Thread(() -> {
                try (Socket s = server.accept()) {
                    s.getInputStream().read(new byte[1024]);
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello"
                            .getBytes(StandardCharsets.US_ASCII));
                } catch (Exception ignored) {
                    // the test is over
                }
            });
            answer.setDaemon(true);
            answer.start();

            PagesProbe.Result r = new HttpPagesProbe(true, Duration.ofSeconds(2))
                    .get("http://localhost:" + server.getLocalPort() + "/t/-/check/n");

            assertThat(r.status()).isEqualTo(200);
            assertThat(r.body()).isEqualTo("hello");
        }
    }
}
