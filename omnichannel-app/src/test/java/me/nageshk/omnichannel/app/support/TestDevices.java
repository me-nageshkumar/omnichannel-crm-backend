package me.nageshk.omnichannel.app.support;

import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.security.phone.DeviceKeys;
import me.nageshk.omnichannel.service.phone.PhoneNumberService;
import org.springframework.stereotype.Component;

/** Onboards numbers and redeems connect codes for tests that need a device key. */
@Component
public class TestDevices {

    private final TestUsers testUsers;
    private final PhoneNumberRepository numbers;
    private final PhoneNumberService service;
    private final DeviceKeys keys;
    private static final java.time.Duration WAIT = java.time.Duration.ofSeconds(5);

    private final me.nageshk.omnichannel.service.inbox.InboxDevices devices;
    private final me.nageshk.omnichannel.service.ws.DeviceRegistry registry;

    public TestDevices(TestUsers testUsers, PhoneNumberRepository numbers, PhoneNumberService service, DeviceKeys keys,
            me.nageshk.omnichannel.service.inbox.InboxDevices devices,
            me.nageshk.omnichannel.service.ws.DeviceRegistry registry) {
        this.devices = devices;
        this.registry = registry;
        this.testUsers = testUsers;
        this.numbers = numbers;
        this.service = service;
        this.keys = keys;
    }

    /** A number saved directly in the given brand. */
    public PhoneNumber number(Workspace w, String number) {
        PhoneNumber n = new PhoneNumber();
        n.setNumber(number);
        n.setCustomerId(w.getCustomerId());
        n.setWorkspaceId(w.getId());
        return numbers.save(n);
    }

    /** A fresh customer "Device Co" with brand "Device Brand" and the number, connected: returns its device key. */
    public DeviceKeys.Connected connected(String number) {
        Customer c = testUsers.customer("Device Co");
        Workspace w = testUsers.workspace(c.getId(), "Device Brand");
        return redeem(number(w, number));
    }

    public DeviceKeys.Connected redeem(PhoneNumber n) {
        return keys.redeem(service.issueConnectCode(n).code(), "127.0.0.1");
    }

    /** {@link #awaitReady(String)} for a number in hand. */
    public void awaitReady(PhoneNumber n) throws InterruptedException {
        awaitReady(n.getId());
    }

    /** As before, for a number known by id (a test that kept only the connected device's ids). */
    public void awaitReady(PhoneNumber n, java.time.Duration timeout) throws InterruptedException {
        await(n.getId(), timeout, id -> devices.isReady(n.getWorkspaceId(), id), "ready");
    }

    /**
     * Waits until the server counts the number as ready to send (its device confirmed and WhatsApp connected): the
     * "ready" event is handled asynchronously, so a fixed sleep loses the race under a busy parallel run.
     */
    public void awaitReady(String phoneNumberId) throws InterruptedException {
        PhoneNumber n = numbers.findById(phoneNumberId).orElseThrow();
        awaitReady(n, WAIT);
    }

    /** Waits until a device is registered for the number, for tests that keep it connected but never ready. */
    public void awaitConnected(PhoneNumber n) throws InterruptedException {
        await(n.getId(), WAIT, id -> registry.find(id).isPresent(), "connected");
    }

    private static void await(String phoneNumberId, java.time.Duration timeout,
            java.util.function.Predicate<String> done, String what) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (!done.test(phoneNumberId)) {
            if (System.nanoTime() > end) {
                throw new IllegalStateException("number " + phoneNumberId + " not " + what + " after " + timeout);
            }
            Thread.sleep(20);
        }
    }
}
