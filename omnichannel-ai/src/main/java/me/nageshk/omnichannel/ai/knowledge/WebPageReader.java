package me.nageshk.omnichannel.ai.knowledge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.nageshk.omnichannel.common.net.HttpAddressGuard;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Fetches a public web page and turns it into Markdown for a knowledge base. Every hop, the first address and each
 * redirect, goes through the {@link HttpAddressGuard} before anything is requested; redirects are followed by hand,
 * at most three.
 */
@Component
public class WebPageReader {

    /** What was read: the page's title, the Markdown to upload (with a Source/Fetched header) and the address it came from. */
    public record Page(String title, String markdown, URI finalUrl) {}

    /** A refusal or failure whose message is shown on the source. */
    public static class WebPageException extends RuntimeException {
        public WebPageException(String message) {
            super(message);
        }
    }

    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_REDIRECTS = 3;
    private static final Duration DEADLINE = Duration.ofSeconds(10);
    /** Closes a body stream whose deadline has passed; the JDK's request timeout stops counting once headers arrive. */
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "web-page-deadline");
                t.setDaemon(true);
                return t;
            });
    private static final String DROPPED = "script, style, noscript, nav, footer, header nav, form, iframe, svg";

    private final HttpAddressGuard guard;
    private final boolean allowHttp;
    private final Clock clock;
    private final Duration deadline;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** Plain http is accepted only where local addresses are (tests); production takes https only. */
    @Autowired
    public WebPageReader(HttpAddressGuard guard, Clock clock) {
        this(guard, guard.allowsPrivate(), clock, DEADLINE);
    }

    /** {@code deadline} bounds the whole fetch, every hop and the body included. */
    WebPageReader(HttpAddressGuard guard, boolean allowHttp, Clock clock, Duration deadline) {
        this.deadline = deadline;
        this.guard = guard;
        this.allowHttp = allowHttp;
        this.clock = clock;
    }

    public Page read(URI url) {
        long endsAt = System.nanoTime() + deadline.toNanos();
        URI current = url;
        for (int hop = 0; ; hop++) {
            checkAddress(current);
            HttpResponse<InputStream> res = send(current, endsAt);
            int status = res.statusCode();
            if (status >= 300 && status < 400 && res.headers().firstValue("Location").isPresent()) {
                closeQuietly(res.body());
                if (hop >= MAX_REDIRECTS) throw new WebPageException("Too many redirects");
                try {
                    current = current.resolve(res.headers().firstValue("Location").get().strip());
                } catch (IllegalArgumentException e) {
                    throw new WebPageException("The page could not be reached");
                }
                continue;
            }
            if (status != 200) {
                closeQuietly(res.body());
                throw new WebPageException("The page answered " + status);
            }
            return page(current, res, endsAt);
        }
    }

    private void checkAddress(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowHttp && scheme.equals("http"))) {
            throw new WebPageException("Only https addresses");
        }
        try {
            guard.check(uri);
        } catch (IllegalArgumentException e) {
            throw new WebPageException("This address is not allowed");
        }
    }

    private HttpResponse<InputStream> send(URI uri, long endsAt) {
        try {
            long left = endsAt - System.nanoTime();
            if (left <= 0) throw new WebPageException("The page could not be reached");
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(left)).GET()
                    .header("Accept", "text/html,text/plain;q=0.9,*/*;q=0.1")
                    .header("User-Agent", "OmnichannelKnowledgeBot/1.0").build();
            return http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebPageException("The page could not be reached");
        } catch (IOException | RuntimeException e) {
            throw new WebPageException("The page could not be reached");
        }
    }

    private Page page(URI url, HttpResponse<InputStream> res, long endsAt) {
        String type = res.headers().firstValue("Content-Type").orElse("text/html").toLowerCase(Locale.ROOT);
        boolean html = type.contains("html");
        if (!html && !type.startsWith("text/")) {
            closeQuietly(res.body());
            throw new WebPageException("The page is not text or HTML");
        }
        long declared = res.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declared > MAX_BYTES) {
            closeQuietly(res.body());
            throw new WebPageException("The page is larger than 2 MB");
        }
        byte[] bytes = readBody(res.body(), endsAt);
        String charset = charsetName(type);
        String header = "Source: " + url + "\nFetched: " + clock.instant() + "\n\n";
        if (!html) {
            String text = new String(bytes, charset == null ? StandardCharsets.UTF_8 : charsetOf(charset)).strip();
            return new Page(url.toString(), header + text, url);
        }
        Document doc;
        try {
            doc = Jsoup.parse(new ByteArrayInputStream(bytes), charset, url.toString());
        } catch (IOException e) {
            throw new WebPageException("The page could not be reached");
        }
        doc.select(DROPPED).remove();
        String title = collapse(doc.title());
        if (title.isEmpty()) {
            Element h1 = doc.selectFirst("h1");
            title = h1 == null ? url.toString() : collapse(h1.text());
        }
        if (title.isEmpty()) title = url.toString();
        Element body = doc.body();
        String markdown = body == null ? "" : new MarkdownWriter().write(body);
        return new Page(title, header + markdown, url);
    }

    private static byte[] readBody(InputStream in, long endsAt) {
        var timer = WATCHDOG.schedule(() -> closeQuietly(in), Math.max(0, endsAt - System.nanoTime()),
                java.util.concurrent.TimeUnit.NANOSECONDS);
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new WebPageException("The page is larger than 2 MB");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new WebPageException("The page could not be reached");
        } finally {
            timer.cancel(false);
        }
    }

    private static String charsetName(String contentType) {
        int i = contentType.indexOf("charset=");
        if (i < 0) return null;
        String c = contentType.substring(i + 8).split(";")[0].strip().replace("\"", "");
        return c.isEmpty() ? null : c;
    }

    private static Charset charsetOf(String name) {
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    private static String collapse(String s) {
        return s == null ? "" : s.replace(' ', ' ').replaceAll("\\s+", " ").strip();
    }

    /** Walks the cleaned page into headings, paragraphs, list items and table rows, one blank line between blocks. */
    private static final class MarkdownWriter {
        private record Line(char kind, String text) {}

        private final List<Line> lines = new ArrayList<>();
        private final StringBuilder inline = new StringBuilder();
        private String prefix = "";
        private char kind = 'p';

        String write(Element body) {
            walk(body);
            flush();
            StringBuilder out = new StringBuilder();
            Line prev = null;
            for (Line l : lines) {
                if (prev != null) {
                    out.append(prev.kind == l.kind && (l.kind == 'l' || l.kind == 't') ? "\n" : "\n\n");
                }
                out.append(l.text);
                prev = l;
            }
            return out.toString();
        }

        private void flush() {
            String text = collapse(inline.toString());
            inline.setLength(0);
            if (!text.isEmpty()) lines.add(new Line(kind, prefix + text));
            prefix = "";
            kind = 'p';
        }

        private void walk(Node node) {
            if (node instanceof TextNode t) {
                inline.append(t.getWholeText());
                return;
            }
            if (!(node instanceof Element e)) return;
            String tag = e.normalName();
            switch (tag) {
                case "h1", "h2", "h3", "h4" -> {
                    flush();
                    prefix = "#".repeat(tag.charAt(1) - '0') + " ";
                    kind = 'h';
                    children(e);
                    flush();
                }
                case "li" -> {
                    flush();
                    prefix = "- ";
                    kind = 'l';
                    children(e);
                    flush();
                }
                case "table" -> {
                    flush();
                    table(e);
                }
                case "br" -> inline.append(' ');
                case "p", "div", "section", "article", "main", "aside", "header", "ul", "ol", "blockquote", "pre",
                        "dl", "dt", "dd", "h5", "h6", "figure", "figcaption", "address", "hr" -> {
                    flush();
                    children(e);
                    flush();
                }
                default -> children(e);
            }
        }

        private void children(Element e) {
            for (Node c : e.childNodes()) walk(c);
        }

        private void table(Element table) {
            boolean first = true;
            for (Element row : table.select("tr")) {
                List<String> cells = new ArrayList<>();
                for (Element cell : row.children()) {
                    if (cell.normalName().equals("td") || cell.normalName().equals("th")) {
                        cells.add(collapse(cell.text()).replace("|", "\\|"));
                    }
                }
                if (cells.isEmpty()) continue;
                lines.add(new Line('t', "| " + String.join(" | ", cells) + " |"));
                if (first) {
                    lines.add(new Line('t', "| " + String.join(" | ", cells.stream().map(c -> "---").toList()) + " |"));
                    first = false;
                }
            }
        }
    }
}
