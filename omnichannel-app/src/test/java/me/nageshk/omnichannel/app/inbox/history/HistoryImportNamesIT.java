package me.nageshk.omnichannel.app.inbox.history;

import static java.util.concurrent.TimeUnit.SECONDS;
import static me.nageshk.omnichannel.app.inbox.history.HistoryImportRunnerIT.A;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.service.contact.ContactService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** The name WhatsApp lists a chat under, on contacts that have none. */
class HistoryImportNamesIT extends HistoryImportTestBase {

    @Autowired ContactRepository contacts;
    @Autowired ContactService contactService;

    private Contact theContact() {
        assertThat(contacts.count()).isEqualTo(1);
        return contacts.findAll().getFirst();
    }

    private void fetchAll(HistoryDevice device) throws Exception {
        importAll(null, null);
        runner.tick().get(10, SECONDS);
    }

    @Test
    void aChatNamesAContactThatHasNone() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "hello")));
            device.names.put(A, "TATA AIG");

            fetchAll(device);

            assertThat(theContact().getName()).isEqualTo("TATA AIG");
        }
    }

    @Test
    void aChatWeOnlyWroteToIsNamedToo() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, true, 100, 3, "hello")));
            device.names.put(A, "Batra Novelties");

            fetchAll(device);

            assertThat(theContact().getName()).isEqualTo("Batra Novelties");
        }
    }

    @Test
    void aNumberIsNotAName() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "hello")));
            device.names.put(A, "+91 98110 00001");

            fetchAll(device);

            assertThat(theContact().getName()).isNull();
        }
    }

    @Test
    void aNumberWithOddSpacingOrMarksIsNotAName() throws Exception {
        for (String title : List.of("+91\u00a098110\u00a000001", "+91\u202f98110 00001", "\u202a+91 98110 00001\u202c",
                "91.9811.0000", "~98110 00001")) {
            mongo.remove(new Query(), Contact.class);
            try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
                device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "hello")));
                device.names.put(A, title);

                fetchAll(device);

                assertThat(theContact().getName()).as(title).isNull();
            }
        }
    }

    @Test
    void aNameWithDigitsIsAName() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "hello")));
            device.names.put(A, "3M India 24x7");

            fetchAll(device);

            assertThat(theContact().getName()).isEqualTo("3M India 24x7");
        }
    }

    @Test
    void aProfileNameLaterDoesNotReplaceTheListedName() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "hello")));
            device.names.put(A, "TATA AIG");
            fetchAll(device);

            contactService.upsertFromChat(brand.getId(), acme.getId(), A, "Tata Sales", null, START.plusSeconds(60));

            assertThat(theContact().getName()).isEqualTo("TATA AIG");
        }
    }

    @Test
    void theListedNameBeatsTheProfileNameOnTheMessage() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "hello").put("notifyName", "Tata Sales")));
            device.names.put(A, "TATA AIG");

            fetchAll(device);

            assertThat(theContact().getName()).isEqualTo("TATA AIG");
        }
    }

    @Test
    void aNameClearedByHandStaysCleared() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "hello")));
            fetchAll(device);
            mongo.updateFirst(new Query(), new Update().set("nameEdited", true), Contact.class);

            device.names.put(A, "TATA AIG");
            fetchAll(device);

            assertThat(theContact().getName()).isNull();
        }
    }

    @Test
    void aFetchFillsInAContactFromAnEarlierOne() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "hello")));
            fetchAll(device);
            assertThat(theContact().getName()).isNull();

            device.names.put(A, "TATA AIG");
            fetchAll(device);

            assertThat(theContact().getName()).isEqualTo("TATA AIG");
        }
    }

    @Test
    void aContactWithANameKeepsIt() throws Exception {
        try (HistoryDevice device = new HistoryDevice(port, testDevices, n1)) {
            device.chats.put(A, List.of(HistoryDevice.msg(A, false, 100, 1, "hello")));
            fetchAll(device);
            mongo.updateFirst(new Query(), new Update().set("name", "Asha"), Contact.class);

            device.names.put(A, "TATA AIG");
            fetchAll(device);

            assertThat(theContact().getName()).isEqualTo("Asha");
        }
    }
}
