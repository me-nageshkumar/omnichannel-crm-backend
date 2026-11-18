package me.nageshk.omnichannel.service.page;

import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import me.nageshk.omnichannel.common.net.HttpAddressGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The JDK client: one deadline for the headers and the body together, no redirects, at most 1 KB of body, and its own
 * private-address guard. A server that answers and then stalls cannot hold the caller past the deadline.
 */
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
        CompletableFuture<HttpResponse<byte[]>> call = client.sendAsync(req, info -> new CappedBody(MAX_BODY));
        try {
            HttpResponse<byte[]> res = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Result(res.statusCode(), new String(res.body(), StandardCharsets.UTF_8), null);
        } catch (TimeoutException e) {
            call.cancel(true);
            return new Result(0, null, NO_ANSWER);
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            return new Result(0, null, NO_ANSWER);
        } catch (ExecutionException e) {
            return new Result(0, null, failureOf(e.getCause()));
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

    /** Collects at most {@code cap} bytes, then stops reading and completes with what it has. */
    static final class CappedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final int cap;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        CappedBody(int cap) {
            this.cap = cap;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer b : items) {
                int n = Math.min(b.remaining(), cap - buf.size());
                byte[] chunk = new byte[n];
                b.get(chunk);
                buf.write(chunk, 0, n);
            }
            if (buf.size() >= cap) {
                subscription.cancel();
                result.complete(buf.toByteArray());
            } else {
                subscription.request(1);
            }
        }

        @Override
        public void onError(Throwable t) {
            result.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            result.complete(buf.toByteArray());
        }
    }
}
