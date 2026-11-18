package me.nageshk.omnichannel.service.page;

import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.net.ssl.SSLException;
import me.nageshk.omnichannel.common.net.HttpAddressGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The JDK client: a timeout, no redirects, 1 KB of body, and its own private-address guard. */
@Component
public class HttpPagesProbe implements PagesProbe {

    private static final int MAX_BODY = 1024;

    private final HttpClient client;
    private final HttpAddressGuard guard;
    private final Duration timeout;

    public HttpPagesProbe(@Value("${omnichannel.pages.allow-insecure:false}") boolean allowInsecure,
            @Value("${omnichannel.pages.check-timeout:20s}") Duration timeout) {
        this.timeout = timeout;
        this.guard = new HttpAddressGuard(allowInsecure);
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Result get(String url) {
        URI uri;
        try {
            uri = URI.create(url);
            guard.check(uri);
        } catch (IllegalArgumentException e) {
            String m = String.valueOf(e.getMessage());
            return new Result(0, null, m.contains("could not be resolved") ? NO_SUCH_NAME : REFUSED_ADDRESS);
        }
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
        try {
            HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = res.body()) {
                byte[] body = in.readNBytes(MAX_BODY);
                return new Result(res.statusCode(), new String(body, StandardCharsets.UTF_8), null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(0, null, NO_ANSWER);
        } catch (Exception e) {
            return new Result(0, null, failureOf(e));
        }
    }

    private static String failureOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SSLException) return BAD_CERTIFICATE;
            if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) return NO_SUCH_NAME;
            if (t instanceof HttpConnectTimeoutException || t instanceof HttpTimeoutException
                    || t instanceof ConnectException) {
                return NO_ANSWER;
            }
        }
        return NO_ANSWER;
    }
}
