package me.nageshk.omnichannel.security.phone;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import me.nageshk.omnichannel.common.api.DeviceDisabledException;
import me.nageshk.omnichannel.common.api.InvalidConnectCodeException;
import me.nageshk.omnichannel.db.phone.ConnectCode;
import me.nageshk.omnichannel.db.phone.ConnectCodeRepository;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.security.auth.AccountLockedException;
import me.nageshk.omnichannel.security.phone.DeviceAccessChanged.Reason;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Connect codes for device keys, and device keys for principals. A key works only while its number,
 * brand and customer are all enabled; a new key replaces the old one.
 */
@Service
public class DeviceKeys {

    public record Connected(String deviceKey, String deviceKeyId, String phoneNumberId, String number,
            String brandName) {}

    private final PhoneNumberRepository numbers;
    private final ConnectCodeRepository codes;
    private final WorkspaceRepository workspaces;
    private final AccessService access;
    private final ConnectAttemptLimiter limiter;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public DeviceKeys(PhoneNumberRepository numbers, ConnectCodeRepository codes, WorkspaceRepository workspaces,
            AccessService access, ConnectAttemptLimiter limiter, ApplicationEventPublisher events, Clock clock) {
        this.numbers = numbers;
        this.codes = codes;
        this.workspaces = workspaces;
        this.access = access;
        this.limiter = limiter;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @throws AccountLockedException when the IP failed too often
     * @throws InvalidConnectCodeException for an unknown, used or expired code (counted as a failure)
     * @throws DeviceDisabledException when the number, brand or customer is disabled
     */
    public Connected redeem(String code, String clientIp) {
        long wait = limiter.blockedForSeconds(clientIp);
        if (wait > 0) throw new AccountLockedException("too many connect attempts; try again later", wait);
        Optional<ConnectCode> found = codes.findByCodeHash(Secrets.sha256(Secrets.normalizeCode(code)));
        if (found.isEmpty() || !found.get().getExpiresAt().isAfter(Instant.now(clock))) {
            found.ifPresent(codes::delete);
            limiter.recordFailure(clientIp);
            throw new InvalidConnectCodeException();
        }
        codes.delete(found.get());
        PhoneNumber n = numbers.findById(found.get().getPhoneNumberId()).orElseThrow(InvalidConnectCodeException::new);
        if (!usable(n)) throw new DeviceDisabledException();

        boolean replacing = n.getDeviceKeyHash() != null;
        String key = Secrets.newDeviceKey();
        n.setDeviceKeyHash(Secrets.sha256(key));
        n.setDeviceKeyId(Secrets.newKeyId());
        n.setDeviceKeyIssuedAt(Instant.now(clock));
        numbers.save(n);
        if (replacing) {
            events.publishEvent(new DeviceAccessChanged(n.getId(), n.getCustomerId(), n.getWorkspaceId(), Reason.REPLACED));
        }
        return new Connected(key, n.getDeviceKeyId(), n.getId(), n.getNumber(), brandName(n));
    }

    /** The principal for a key whose number, brand and customer are all enabled. */
    public Optional<DevicePrincipal> authenticate(String deviceKey) {
        if (deviceKey == null || !deviceKey.startsWith(Secrets.KEY_PREFIX)) return Optional.empty();
        return numbers.findByDeviceKeyHash(Secrets.sha256(deviceKey))
                .filter(this::usable)
                .map(n -> new DevicePrincipal(n.getId(), n.getCustomerId(), n.getWorkspaceId(), n.getNumber(),
                        n.getDeviceKeyId(), brandName(n)));
    }

    /** Whether a refused key is unknown (revoked, replaced, never issued) or belongs to a disabled number. */
    public boolean isDisabledKey(String deviceKey) {
        if (deviceKey == null || !deviceKey.startsWith(Secrets.KEY_PREFIX)) return false;
        return numbers.findByDeviceKeyHash(Secrets.sha256(deviceKey)).filter(n -> !usable(n)).isPresent();
    }

    boolean usable(PhoneNumber n) {
        return n.getDisabled() == null && access.isWorkspaceUsable(n.getWorkspaceId());
    }

    private String brandName(PhoneNumber n) {
        return workspaces.findById(n.getWorkspaceId()).map(Workspace::getName).orElse(null);
    }
}
