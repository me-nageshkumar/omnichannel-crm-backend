package me.nageshk.omnichannel.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.nageshk.omnichannel.common.net.HttpAddressGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebPageReaderTest {

    private HttpServer server;
    private final List<String> hosts = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI at(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private void serve(String path, int status, String type, byte[] body, String location) {
        server.createContext(path, ex -> {
            hosts.add(ex.getRequestHeaders().getFirst("Host"));
            ex.getRequestBody().readAllBytes();
            if (type != null) ex.getResponseHeaders().add("Content-Type", type);
            if (location != null) ex.getResponseHeaders().add("Location", location);
            ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) ex.getResponseBody().write(body);
            ex.close();
        });
    }

    private WebPageReader reader(HttpAddressGuard guard, boolean allowHttp) {
        return new WebPageReader(guard, allowHttp, Clock.systemUTC());
    }

    private static byte[] fixture() throws IOException {
        try (var in = WebPageReaderTest.class.getResourceAsStream("/knowledge/course-page.html")) {
            return in.readAllBytes();
        }
    }

    @Test
    void pageBecomesMarkdownWithoutNavScriptsOrFooter() throws IOException {
        serve("/course", 200, "text/html; charset=utf-8", fixture(), null);
        URI url = at("/course");

        WebPageReader.Page page = reader(new HttpAddressGuard(true), true).read(url);

        assertThat(page.title()).isEqualTo("Crypto & Forex Trading Program | Acme Academy");
        assertThat(page.finalUrl()).isEqualTo(url);
        String md = page.markdown();
        assertThat(md).startsWith("Source: " + url + "\nFetched: ");
        assertThat(md).contains("# Crypto & Forex Trading Program", "## What you get", "- Zoom Sessions",
                "- Weekly doubt clearing", "| Mode | Fee |", "| --- | --- |", "| Online | ₹19,999 |",
                "| Classroom | ₹29,999 |", "A hands-on program");
        assertThat(md).doesNotContain("NAVHOME", "NAVPRICING", "SCRIPTTEXT", "STYLETEXT", "NOSCRIPTTEXT", "FORMTEXT",
                "SVGTEXT", "FOOTERTEXT", "\n\n\n");
    }

    @Test
    void privateAddressIsRefusedByTheGuard() {
        WebPageReader r = reader(new HttpAddressGuard(false), false);
        URI url = URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/");

        assertThatThrownBy(() -> r.read(url)).isInstanceOf(WebPageReader.WebPageException.class)
                .hasMessage("This address is not allowed");
    }

    @Test
    void plainHttpIsRefusedInProduction() {
        WebPageReader r = reader(new HttpAddressGuard(true), false);

        assertThatThrownBy(() -> r.read(at("/")))
                .isInstanceOf(WebPageReader.WebPageException.class).hasMessage("Only https addresses");
    }

    @Test
    void redirectIsCheckedAtEveryHop() {
        List<String> checked = new ArrayList<>();
        HttpAddressGuard firstHostOnly = new HttpAddressGuard(true) {
            @Override
            public void check(URI uri) {
                checked.add(uri.getHost());
                if (!uri.getHost().equals("127.0.0.1")) throw new IllegalArgumentException("private address: " + uri.getHost());
            }
        };
        serve("/start", 302, null, new byte[0], "http://localhost:" + server.getAddress().getPort() + "/second");
        serve("/second", 200, "text/plain", "never read".getBytes(StandardCharsets.UTF_8), null);

        assertThatThrownBy(() -> reader(firstHostOnly, true).read(at("/start")))
                .isInstanceOf(WebPageReader.WebPageException.class).hasMessage("This address is not allowed");
        assertThat(checked).containsExactly("127.0.0.1", "localhost");
        assertThat(hosts).hasSize(1);
        assertThat(hosts.get(0)).startsWith("127.0.0.1");
    }

    @Test
    void relativeRedirectIsFollowed() {
        serve("/old", 301, null, new byte[0], "/new");
        serve("/new", 200, "text/plain", "moved here".getBytes(StandardCharsets.UTF_8), null);

        WebPageReader.Page page = reader(new HttpAddressGuard(true), true).read(at("/old"));

        assertThat(page.finalUrl()).isEqualTo(at("/new"));
        assertThat(page.markdown()).endsWith("\n\nmoved here");
    }

    @Test
    void fourthRedirectIsRefused() {
        serve("/r0", 302, null, new byte[0], "/r1");
        serve("/r1", 302, null, new byte[0], "/r2");
        serve("/r2", 302, null, new byte[0], "/r3");
        serve("/r3", 302, null, new byte[0], "/r4");
        serve("/r4", 200, "text/plain", "done".getBytes(StandardCharsets.UTF_8), null);

        assertThatThrownBy(() -> reader(new HttpAddressGuard(true), true).read(at("/r0")))
                .isInstanceOf(WebPageReader.WebPageException.class).hasMessage("Too many redirects");
        serve("/s0", 302, null, new byte[0], "/s1");
        serve("/s1", 302, null, new byte[0], "/s2");
        serve("/s2", 302, null, new byte[0], "/r4");
        assertThat(reader(new HttpAddressGuard(true), true).read(at("/s0")).markdown()).endsWith("done");
    }

    @Test
    void largerThan2MbRefused() {
        serve("/big", 200, "text/plain", new byte[2 * 1024 * 1024 + 1], null);

        assertThatThrownBy(() -> reader(new HttpAddressGuard(true), true).read(at("/big")))
                .isInstanceOf(WebPageReader.WebPageException.class).hasMessage("The page is larger than 2 MB");
    }

    @Test
    void nonHtmlTextIsKeptAsIs() {
        serve("/notes.txt", 200, "text/plain; charset=utf-8", "Line one\n\nLine two ₹5".getBytes(StandardCharsets.UTF_8), null);
        URI url = at("/notes.txt");

        WebPageReader.Page page = reader(new HttpAddressGuard(true), true).read(url);

        assertThat(page.markdown()).startsWith("Source: " + url + "\nFetched: ").endsWith("\n\nLine one\n\nLine two ₹5");
        assertThat(page.title()).isEqualTo(url.toString());
    }

    @Test
    void errorStatusAndUnreachableAreNamed() {
        serve("/gone", 404, "text/plain", "no".getBytes(StandardCharsets.UTF_8), null);
        WebPageReader r = reader(new HttpAddressGuard(true), true);
        assertThatThrownBy(() -> r.read(at("/gone"))).hasMessage("The page answered 404");

        int dead = server.getAddress().getPort();
        server.stop(0);
        assertThatThrownBy(() -> r.read(URI.create("http://127.0.0.1:" + dead + "/")))
                .hasMessage("The page could not be reached");
    }
}
