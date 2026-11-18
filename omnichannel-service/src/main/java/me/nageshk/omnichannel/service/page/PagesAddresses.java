package me.nageshk.omnichannel.service.page;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.page.FormerHost;
import me.nageshk.omnichannel.db.page.PagesAddress;
import me.nageshk.omnichannel.db.page.PagesAddressRepository;
import me.nageshk.omnichannel.db.page.PagesAddressStatus;
import me.nageshk.omnichannel.db.page.PagesSettings;
import me.nageshk.omnichannel.db.page.PagesSettingsRepository;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.service.page.PagesAddressViews.AddressView;
import me.nageshk.omnichannel.service.page.PagesAddressViews.BrandRow;
import me.nageshk.omnichannel.service.page.PagesAddressViews.CustomerView;
import me.nageshk.omnichannel.service.page.PagesAddressViews.FormerHostView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Which address a link uses, and which hosts are ours (spec 2026-11-18 link addresses §5.3, §5.5): the only place
 * that reads the platform's startup pages address. Read from MongoDB per call: once per send and per page view.
 */
@Service
public class PagesAddresses {

    /** The platform's address and where it comes from; {@code warning}: the save's failed check, else null. */
    public record PlatformView(String baseUrl, String source, String startupBaseUrl, Long version, String warning) {
    }

    private final PagesAddressRepository addresses;
    private final PagesSettingsRepository settings;
    private final WorkspaceRepository workspaces;
    private final MongoTemplate mongo;
    private final OmnichannelProperties props;
    private final Clock clock;
    private final boolean allowInsecure;
    private final PagesAddressChecker checker;

    public PagesAddresses(PagesAddressRepository addresses, PagesSettingsRepository settings,
            WorkspaceRepository workspaces, MongoTemplate mongo, OmnichannelProperties props, Clock clock,
            @Value("${omnichannel.pages.allow-insecure:false}") boolean allowInsecure, PagesAddressChecker checker) {
        this.checker = checker;
        this.addresses = addresses;
        this.settings = settings;
        this.workspaces = workspaces;
        this.mongo = mongo;
        this.props = props;
        this.clock = clock;
        this.allowInsecure = allowInsecure;
    }

    public boolean allowInsecure() {
        return allowInsecure;
    }

    // ---- reading ----

    /** The platform's address: the one saved on the screen, else the startup value. */
    public String platform() {
        String saved = settings.findById(PagesSettings.ID).map(PagesSettings::getBaseUrl).orElse(null);
        return saved != null ? saved : startup();
    }

    public String platformHost() {
        return hostOf(platform());
    }

    /** The brand's address while it is working, else the platform's. */
    public String forWorkspace(String workspaceId) {
        if (workspaceId == null) return platform();
        return addresses.findByWorkspaceId(workspaceId)
                .filter(a -> a.getBaseUrl() != null && a.getStatus() == PagesAddressStatus.WORKING)
                .map(PagesAddress::getBaseUrl).orElseGet(this::platform);
    }

    /** The platform's host (current or former in time), any brand's current host, or a brand's former host in time. */
    public boolean hostKnown(String host) {
        String h = normal(host);
        if (h == null) return false;
        if (h.equals(platformHost()) || formerPlatformHost(h)) return true;
        return customerHost(h);
    }

    /** A brand's current or in-time former host that is not the platform's. */
    public boolean customerHost(String host) {
        String h = normal(host);
        if (h == null || h.equals(platformHost())) return false;
        Instant now = clock.instant();
        Query q = new Query(new Criteria().orOperator(Criteria.where("host").is(h),
                Criteria.where("formerHosts").elemMatch(Criteria.where("host").is(h).and("until").gt(now))));
        return mongo.exists(q, PagesAddress.class);
    }

    public CustomerView forCustomer(String customerId) {
        Map<String, PagesAddress> byWorkspace = addresses.findByCustomerId(customerId).stream()
                .collect(Collectors.toMap(PagesAddress::getWorkspaceId, Function.identity(), (x, y) -> x));
        List<BrandRow> rows = new ArrayList<>();
        for (Workspace w : workspaces.findByCustomerIdOrderByName(customerId)) {
            rows.add(row(w, byWorkspace.get(w.getId())));
        }
        return new CustomerView(platform(), rows);
    }

    public BrandRow row(String customerId, String workspaceId) {
        Workspace w = workspace(customerId, workspaceId);
        return row(w, addresses.findByWorkspaceId(workspaceId).orElse(null));
    }

    public PlatformView platformView() {
        return platformView(null);
    }

    PlatformView platformView(String warning) {
        PagesSettings s = settings.findById(PagesSettings.ID).orElse(null);
        boolean saved = s != null && s.getBaseUrl() != null;
        return new PlatformView(platform(), saved ? "SAVED" : "STARTUP", startup(), s == null ? null : s.getVersion(),
                warning);
    }

    // ---- writing ----

    /** Sets the brand's address (unchecked); the old host stays known for the pages' lifetime. */
    public PagesAddress set(String customerId, String workspaceId, String text, Long version, String byUserId) {
        Workspace w = workspace(customerId, workspaceId);
        PagesUrl url = PagesUrl.parse(text, allowInsecure);
        if (url.host().equals(platformHost())) throw new ValidationException("baseUrl", "This is the platform's own address");
        if (takenByAnotherCustomer(url.host(), w.getCustomerId())) {
            throw new CodedException(HttpStatus.CONFLICT, "PAGES_HOST_TAKEN", "baseUrl", "Another customer uses this domain");
        }
        PagesAddress a = current(w, version);
        Instant now = clock.instant();
        retire(a, url.host(), now);
        a.getFormerHosts().removeIf(f -> f.host().equals(url.host()));
        a.setBaseUrl(url.baseUrl());
        a.setHost(url.host());
        a.setStatus(PagesAddressStatus.UNCHECKED);
        a.setFailures(0);
        a.setCheckedAt(null);
        a.setLastError(null);
        a.setCheckNonce(null);
        a.setSetAt(now);
        a.setSetBy(byUserId);
        PagesAddress saved = addresses.save(a);
        // checked at once; its one result decides (spec §5.4)
        PagesAddress checked = checker.check(saved.getWorkspaceId());
        return checked != null ? checked : saved;
    }

    /** Takes the brand's address away; its links use the platform's, and its host stays known for the pages' lifetime. */
    public PagesAddress remove(String customerId, String workspaceId, Long version, String byUserId) {
        Workspace w = workspace(customerId, workspaceId);
        PagesAddress a = current(w, version);
        Instant now = clock.instant();
        retire(a, null, now);
        a.setBaseUrl(null);
        a.setHost(null);
        a.setStatus(null);
        a.setFailures(0);
        a.setCheckedAt(null);
        a.setLastError(null);
        a.setCheckNonce(null);
        a.setSetAt(now);
        a.setSetBy(byUserId);
        return addresses.save(a);
    }

    /** Saves the platform's address; {@code null} goes back to the startup value. */
    public PlatformView savePlatform(String textOrNull, Long version, String byUserId) {
        PagesSettings s = settings.findById(PagesSettings.ID).orElseGet(PagesSettings::new);
        if (!Objects.equals(s.getVersion(), version) && !(s.getVersion() == null && version == null)) {
            throw new OptimisticLockingFailureException("the platform address was changed meanwhile");
        }
        String oldHost = platformHost();
        Instant now = clock.instant();
        if (textOrNull == null) {
            s.setBaseUrl(null);
        } else {
            PagesUrl url = PagesUrl.parse(textOrNull, allowInsecure);
            if (mongo.exists(new Query(Criteria.where("host").is(url.host())), PagesAddress.class)) {
                throw new ValidationException("baseUrl", "A brand uses this domain");
            }
            s.setBaseUrl(url.baseUrl());
        }
        String newHost = hostOf(s.getBaseUrl() != null ? s.getBaseUrl() : startup());
        s.getFormerHosts().removeIf(f -> !f.until().isAfter(now) || f.host().equals(newHost));
        if (oldHost != null && !oldHost.equals(newHost)) s.getFormerHosts().add(new FormerHost(oldHost, until(now)));
        s.setUpdatedAt(now);
        s.setUpdatedBy(byUserId);
        settings.save(s);
        String warning = s.getBaseUrl() == null ? null : checker.checkPlatform(s.getBaseUrl());
        return platformView(warning);
    }

    // ---- helpers ----

    private PagesAddress current(Workspace w, Long version) {
        PagesAddress a = addresses.findByWorkspaceId(w.getId()).orElse(null);
        if (a == null) {
            if (version != null) throw new OptimisticLockingFailureException("the link address was changed meanwhile");
            a = new PagesAddress();
            a.setWorkspaceId(w.getId());
            a.setCustomerId(w.getCustomerId());
            return a;
        }
        if (!Objects.equals(a.getVersion(), version)) {
            throw new OptimisticLockingFailureException("the link address was changed meanwhile");
        }
        return a;
    }

    /** Moves the current host to the former ones unless it stays; drops former hosts whose time is over. */
    private void retire(PagesAddress a, String keptHost, Instant now) {
        a.getFormerHosts().removeIf(f -> !f.until().isAfter(now));
        if (a.getHost() != null && !a.getHost().equals(keptHost)) {
            a.getFormerHosts().removeIf(f -> f.host().equals(a.getHost()));
            a.getFormerHosts().add(new FormerHost(a.getHost(), until(now)));
        }
    }

    private boolean takenByAnotherCustomer(String host, String customerId) {
        Instant now = clock.instant();
        Query q = new Query(new Criteria().andOperator(Criteria.where("customerId").ne(customerId),
                new Criteria().orOperator(Criteria.where("host").is(host),
                        Criteria.where("formerHosts").elemMatch(Criteria.where("host").is(host).and("until").gt(now)))));
        return mongo.exists(q, PagesAddress.class);
    }

    private boolean formerPlatformHost(String host) {
        Instant now = clock.instant();
        return settings.findById(PagesSettings.ID).map(s -> s.getFormerHosts().stream()
                .anyMatch(f -> f.host().equals(host) && f.until().isAfter(now))).orElse(false);
    }

    private Instant until(Instant now) {
        return now.plus(Duration.ofDays(props.pages().ttlDays()));
    }

    private Workspace workspace(String customerId, String workspaceId) {
        return workspaces.findByIdAndCustomerId(workspaceId, customerId)
                .orElseThrow(() -> new NotFoundException("workspace not found"));
    }

    private BrandRow row(Workspace w, PagesAddress a) {
        Instant now = clock.instant();
        AddressView view = a == null || a.getBaseUrl() == null ? null
                : new AddressView(a.getBaseUrl(), a.getStatus(), a.getCheckedAt(), a.getLastError(),
                        a.getStatus() == PagesAddressStatus.WORKING);
        List<FormerHostView> former = a == null ? List.of() : a.getFormerHosts().stream()
                .filter(f -> f.until().isAfter(now)).map(f -> new FormerHostView(f.host(), f.until())).toList();
        return new BrandRow(w.getId(), w.getName(), view, former, a == null ? null : a.getVersion());
    }

    private String startup() {
        String s = props.pages().baseUrl();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String hostOf(String baseUrl) {
        if (baseUrl == null) return null;
        String rest = baseUrl.replaceFirst("^[a-zA-Z]+://", "");
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        int colon = authority.lastIndexOf(':');
        return (colon < 0 ? authority : authority.substring(0, colon)).toLowerCase(Locale.ROOT);
    }

    private static String normal(String host) {
        if (host == null || host.isBlank()) return null;
        String h = host.strip().toLowerCase(Locale.ROOT);
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(']') < 0) h = h.substring(0, colon);
        // "links.acme.in." is the same name: a trailing dot must not slip past the pages-only filter
        h = h.replaceAll("\\.+$", "");
        return h.isEmpty() ? null : h;
    }
}
