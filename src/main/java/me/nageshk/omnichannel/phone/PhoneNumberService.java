package me.nageshk.omnichannel.phone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.api.DuplicateException;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.enablement.EnableLevel;
import me.nageshk.omnichannel.enablement.Enablement;
import me.nageshk.omnichannel.phone.DeviceAccessChanged.Reason;
import me.nageshk.omnichannel.phone.PhoneNumberStatus.Status;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.CustomerRepository;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.tenant.WorkspaceRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Brands' WhatsApp numbers: add, relabel, delete, enable/disable, revoke the device, and the views
 * for the brand and platform pages. Authorities are checked by the controllers; a number that is not
 * the given brand's is not found. Anything that may cut a live connection publishes
 * {@link DeviceAccessChanged}.
 */
@Service
public class PhoneNumberService {

    public static final Pattern NUMBER = Pattern.compile("^[1-9][0-9]{7,14}$");
    public static final int MAX_LABEL = 60;
    public static final int PAGE_SIZE = 50;

    private final PhoneNumberRepository numbers;
    private final WorkspaceRepository workspaces;
    private final CustomerRepository customers;
    private final Enablement enablement;
    private final LiveDevices live;
    private final ApplicationEventPublisher events;

    public PhoneNumberService(PhoneNumberRepository numbers, WorkspaceRepository workspaces,
            CustomerRepository customers, Enablement enablement, LiveDevices live, ApplicationEventPublisher events) {
        this.numbers = numbers;
        this.workspaces = workspaces;
        this.customers = customers;
        this.enablement = enablement;
        this.live = live;
        this.events = events;
    }

    /** @throws DuplicateException (field {@code number}) if any brand already has the number */
    public PhoneNumber create(String customerId, String workspaceId, String number, String label,
            AuthenticatedUser me) {
        PhoneNumber n = new PhoneNumber();
        n.setNumber(requireNumber(number));
        n.setLabel(cleanLabel(label));
        n.setCustomerId(customerId);
        n.setWorkspaceId(workspaceId);
        n.setCreatedBy(me.getUsername());
        if (numbers.existsByNumber(n.getNumber())) throw duplicate();
        try {
            return numbers.insert(n);
        } catch (DuplicateKeyException e) {
            throw duplicate();
        }
    }

    public List<PhoneNumber> listForWorkspace(String workspaceId) {
        return numbers.findByWorkspaceIdOrderByNumber(workspaceId);
    }

    public PhoneNumber requireInWorkspace(String workspaceId, String id) {
        return numbers.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new NotFoundException("phone number not found"));
    }

    public PhoneNumber require(String id) {
        return numbers.findById(id).orElseThrow(() -> new NotFoundException("phone number not found"));
    }

    public PhoneNumber relabel(PhoneNumber n, String label) {
        n.setLabel(cleanLabel(label));
        return numbers.save(n);
    }

    public void delete(PhoneNumber n) {
        numbers.delete(n);
        publish(n, Reason.DELETED);
    }

    public PhoneNumber setEnabled(PhoneNumber n, boolean enabled, AuthenticatedUser me, EnableLevel level) {
        boolean before = n.getDisabled() == null;
        if (enabled) {
            if (before) return n;
            enablement.checkEnable(n.getDisabled(), level);
            n.setDisabled(null);
        } else {
            n.setDisabled(enablement.disable(n.getDisabled(), me, level));
        }
        PhoneNumber saved = numbers.save(n);
        if (before != enabled) enablement.record("PHONE_NUMBER", n.getId(), before, enabled, me, level);
        if (!enabled) publish(n, Reason.DISABLED);
        return saved;
    }

    public PhoneNumber revokeDevice(PhoneNumber n) {
        n.setDeviceKeyHash(null);
        n.setDeviceKeyId(null);
        n.setDeviceKeyIssuedAt(null);
        PhoneNumber saved = numbers.save(n);
        publish(n, Reason.REVOKED);
        return saved;
    }

    public PhoneNumberView view(PhoneNumber n) {
        Workspace w = workspaces.findById(n.getWorkspaceId()).orElse(null);
        Customer c = customers.findById(n.getCustomerId()).orElse(null);
        return view(n, w, c);
    }

    public List<PhoneNumberView> views(List<PhoneNumber> list) {
        Map<String, Workspace> ws = workspaces.findAllById(list.stream().map(PhoneNumber::getWorkspaceId).distinct().toList())
                .stream().collect(Collectors.toMap(Workspace::getId, Function.identity()));
        Map<String, Customer> cs = customers.findAllById(list.stream().map(PhoneNumber::getCustomerId).distinct().toList())
                .stream().collect(Collectors.toMap(Customer::getId, Function.identity()));
        return list.stream().map(n -> view(n, ws.get(n.getWorkspaceId()), cs.get(n.getCustomerId()))).toList();
    }

    /**
     * Every number, filtered by {@code q} (number, customer or brand name, case-insensitive) and
     * status, ordered by customer, brand and number, {@link #PAGE_SIZE} per page.
     */
    public PhoneNumberView.Page search(String q, String status, int page) {
        Status wanted = parseStatus(status);
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<PhoneNumberView> all = new ArrayList<>(views(numbers.findAll()));
        all.removeIf(v -> wanted != null && v.status() != wanted);
        all.removeIf(v -> !needle.isEmpty() && !(v.number().contains(needle)
                || contains(v.customerName(), needle) || contains(v.workspaceName(), needle)));
        all.sort(Comparator.comparing((PhoneNumberView v) -> String.valueOf(v.customerName()), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(v -> String.valueOf(v.workspaceName()), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PhoneNumberView::number));
        int p = Math.max(page, 0);
        List<PhoneNumberView> items = all.stream().skip((long) p * PAGE_SIZE).limit(PAGE_SIZE).toList();
        return new PhoneNumberView.Page(items, all.size(), p);
    }

    private PhoneNumberView view(PhoneNumber n, Workspace w, Customer c) {
        PhoneNumberStatus s = PhoneNumberStatus.of(n, w, c, live.stateOf(n.getId()));
        return new PhoneNumberView(n.getId(), n.getNumber(), n.getLabel(), n.getCustomerId(),
                c == null ? null : c.getName(), n.getWorkspaceId(), w == null ? null : w.getName(),
                s.status(), n.getDisabled(), s.disabledBecause(), n.getLastConnectedAt(), n.getLastSeenAt(),
                n.getLastWrongNumber(), n.getLastWrongNumberAt(), n.getDeviceKeyHash() != null, n.getCreationTime());
    }

    private void publish(PhoneNumber n, Reason reason) {
        events.publishEvent(new DeviceAccessChanged(n.getId(), n.getCustomerId(), n.getWorkspaceId(), reason));
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static Status parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return Status.valueOf(status.trim());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("status", "unknown status");
        }
    }

    private static String requireNumber(String number) {
        String n = number == null ? "" : number.trim();
        if (!NUMBER.matcher(n).matches()) {
            throw new ValidationException("number", "digits only, country code first, 8 to 15 digits");
        }
        return n;
    }

    private static String cleanLabel(String label) {
        if (label == null || label.isBlank()) return null;
        String l = label.trim();
        if (l.length() > MAX_LABEL) throw new ValidationException("label", "at most " + MAX_LABEL + " characters");
        return l;
    }

    private static DuplicateException duplicate() {
        return new DuplicateException("number", "this number is already onboarded");
    }
}
