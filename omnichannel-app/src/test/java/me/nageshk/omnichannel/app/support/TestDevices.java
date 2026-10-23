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

    public TestDevices(TestUsers testUsers, PhoneNumberRepository numbers, PhoneNumberService service, DeviceKeys keys) {
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
}
