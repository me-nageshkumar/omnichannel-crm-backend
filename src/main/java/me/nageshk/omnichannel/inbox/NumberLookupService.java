package me.nageshk.omnichannel.inbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import lombok.RequiredArgsConstructor;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.contact.Phones;
import me.nageshk.omnichannel.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.rpc.RpcDispatcher;
import me.nageshk.omnichannel.rpc.RpcException;
import me.nageshk.omnichannel.ws.DeviceSession;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Asks a number's device whether a phone is on WhatsApp ({@code getNumberId}) and remembers the answer per workspace
 * and phone: a registered answer for 30 days, an unregistered one for 7.
 */
@Service
@RequiredArgsConstructor
public class NumberLookupService {

    static final Duration REGISTERED_FRESH = Duration.ofDays(30);
    static final Duration UNREGISTERED_FRESH = Duration.ofDays(7);

    private static final String OUTDATED_EXTENSION = "The WhatsApp extension needs an update to check numbers";

    private final WaNumberLookupRepository lookups;
    private final PhoneNumberRepository numbers;
    private final InboxDevices devices;
    private final RpcDispatcher dispatcher;
    private final ObjectMapper mapper;
    private final Clock clock;

    /**
     * Callers check {@code inbox:send}. A fresh cached answer is returned without touching the device unless
     * {@code force}; otherwise the number's device is asked and nothing is cached if it fails.
     *
     * @throws ValidationException if the phone is not 8 to 15 digits
     * @throws NotFoundException if the number is not in the workspace (even when the answer is cached)
     * @throws NoDeviceException if the cache cannot answer and the number's device is not ready
     */
    public CompletableFuture<NumberLookup> lookup(String workspaceId, String phoneNumberId, String rawPhone,
            boolean force) {
        String phone = Phones.normalise(rawPhone);
        if (!Phones.isValid(phone)) throw new ValidationException("phone", Phones.MESSAGE);
        numbers.findByIdAndWorkspaceId(phoneNumberId, workspaceId)
                .orElseThrow(() -> new NotFoundException("phone number not found"));
        if (!force) {
            Instant now = clock.instant();
            Optional<WaNumberLookup> hit = lookups.findByWorkspaceIdAndPhone(workspaceId, phone)
                    .filter(l -> fresh(l, now));
            if (hit.isPresent()) return CompletableFuture.completedFuture(view(hit.get(), true));
        }
        DeviceSession device = devices.requireReady(workspaceId, phoneNumberId);
        ObjectNode params = mapper.createObjectNode().put("phone", phone);
        return dispatcher.call(device, "getNumberId", params).handle((result, error) -> {
            if (error != null) throw failure(error);
            return view(InboxIngestService.retrying(() -> store(workspaceId, phoneNumberId, phone, result)), false);
        });
    }

    /** An RpcException or a TimeoutException, possibly wrapped in a CompletionException. */
    private static LookupFailedException failure(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof RpcException rpc && RpcException.METHOD_NOT_FOUND.equals(rpc.code())) {
            return new LookupFailedException(OUTDATED_EXTENSION);
        }
        return new LookupFailedException();
    }

    private WaNumberLookup store(String workspaceId, String phoneNumberId, String phone, JsonNode result) {
        boolean registered = result != null && result.isObject();
        WaNumberLookup row = lookups.findByWorkspaceIdAndPhone(workspaceId, phone).orElseGet(WaNumberLookup::new);
        row.setWorkspaceId(workspaceId);
        row.setPhone(phone);
        row.setPhoneNumberId(phoneNumberId);
        row.setRegistered(registered);
        row.setWaPhone(registered ? result.path("phone").asString() : null);
        row.setLid(registered && result.hasNonNull("lid") ? result.get("lid").asString() : null);
        row.setCheckedAt(clock.instant());
        return lookups.save(row);
    }

    private static boolean fresh(WaNumberLookup l, Instant now) {
        return l.getCheckedAt() != null
                && now.isBefore(l.getCheckedAt().plus(l.isRegistered() ? REGISTERED_FRESH : UNREGISTERED_FRESH));
    }

    private static NumberLookup view(WaNumberLookup l, boolean cached) {
        String remoteId = !l.isRegistered() ? null : l.getLid() != null ? l.getLid() : l.getWaPhone();
        return new NumberLookup(l.getPhone(), l.isRegistered(), remoteId, l.getCheckedAt(), cached);
    }
}
