package me.nageshk.omnichannel.service.chatbot.node;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Keeps the HTTP request node off the server's own network (spec §6): loopback, link-local, site-local and
 * unique-local addresses and local host names are refused, after resolution. Tests may switch the check off.
 */
@Component
public class HttpAddressGuard {

    private final boolean allowPrivate;

    @Autowired
    public HttpAddressGuard(OmnichannelProperties props) {
        this(props.chatbot().allowPrivateHttp());
    }

    public HttpAddressGuard(boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    public boolean allowsPrivate() {
        return allowPrivate;
    }

    /** @throws IllegalArgumentException with "private address" (or the scheme/host problem) when the URL may not be called */
    public void check(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new IllegalArgumentException("the URL must start with http:// or https://");
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("the URL has no host");
        if (allowPrivate) return;
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".internal") || lower.endsWith(".local")) {
            throw new IllegalArgumentException("private address: " + host);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(stripBrackets(host));
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("the host could not be resolved: " + host);
        }
        for (InetAddress a : addresses) {
            if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress()
                    || a.isMulticastAddress() || uniqueLocal(a) || cgnat(a)) {
                throw new IllegalArgumentException("private address: " + host);
            }
        }
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    /** fc00::/7 */
    private static boolean uniqueLocal(InetAddress a) {
        return a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc;
    }

    /** 100.64.0.0/10, the carrier NAT range some clouds use internally. */
    private static boolean cgnat(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
    }
}
