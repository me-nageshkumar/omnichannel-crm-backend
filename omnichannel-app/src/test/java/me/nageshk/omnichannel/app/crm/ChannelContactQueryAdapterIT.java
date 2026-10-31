package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.crm.platform.ChannelAccount;
import me.nageshk.omnichannel.crm.platform.ChannelAttributeValue;
import me.nageshk.omnichannel.crm.platform.ChannelContactDetails;
import me.nageshk.omnichannel.crm.platform.ChannelContactQuery;
import me.nageshk.omnichannel.crm.platform.ChannelKind;
import me.nageshk.omnichannel.crm.platform.ChannelRule;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactAttribute;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.contact.ContactLabel;
import me.nageshk.omnichannel.db.contact.ContactLabelRepository;
import me.nageshk.omnichannel.db.contact.ContactSource;
import me.nageshk.omnichannel.db.contact.DoNotContact;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The channel-contact side of segments: host labels, attributes, dates and flags through the host's own compiler. */
class ChannelContactQueryAdapterIT extends CrmTestBase {

    @Autowired ChannelContactQuery channel;
    @Autowired ContactLabelRepository labelRepository;
    @Autowired ContactAttributeRepository attributeRepository;

    private ContactLabel label(String name) {
        ContactLabel l = new ContactLabel();
        l.setWorkspaceId(brand.getId());
        l.setName(name);
        l.setNameKey(name.toLowerCase());
        l.setColor("#ff0000");
        return labelRepository.save(l);
    }

    private ContactAttribute attribute(String name) {
        ContactAttribute a = new ContactAttribute();
        a.setWorkspaceId(brand.getId());
        a.setName(name);
        a.setNameKey(name.toLowerCase());
        a.setKey(name.toLowerCase());
        return attributeRepository.save(a);
    }

    private Set<String> match(String field, String operator, Object value) {
        return channel.matching(brand.getId(), new ChannelRule(field, operator, value), 1000);
    }

    @Test
    void labelHasMatchesByLabelId() {
        ContactLabel vip = label("VIP");
        ContactLabel gold = label("Gold");
        Contact a = contact("A", "919900000701");
        a.setLabels(Set.of(vip.getId()));
        contacts.save(a);
        Contact b = contact("B", "919900000702");
        b.setLabels(Set.of(gold.getId()));
        contacts.save(b);
        contact("C", "919900000703");
        assertThat(match("label", "has", vip.getId())).containsExactly(a.getId());
        assertThat(match("label", "hasAny", List.of(vip.getId(), gold.getId()))).containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(match("label", "hasNone", List.of(vip.getId()))).contains(b.getId()).doesNotContain(a.getId());
        assertThat(channel.labels(brand.getId())).extracting("id", "name")
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(vip.getId(), "VIP"),
                        org.assertj.core.groups.Tuple.tuple(gold.getId(), "Gold"));
    }

    @Test
    void attributeIsMatchesByAttributeId() {
        ContactAttribute city = attribute("City");
        Contact a = contact("A", "919900000711");
        a.setAttributes(Map.of(city.getId(), "Mumbai"));
        contacts.save(a);
        Contact b = contact("B", "919900000712");
        b.setAttributes(Map.of(city.getId(), "Pune"));
        contacts.save(b);
        assertThat(match("attribute:" + city.getId(), "is", "mumbai")).containsExactly(a.getId());
        assertThat(match("attribute:" + city.getId(), "contains", "un")).containsExactly(b.getId());
        assertThat(match("attribute:" + city.getId(), "isSet", null)).containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(channel.attributes(brand.getId())).extracting("id", "name")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(city.getId(), "City"));
    }

    @Test
    void lastMessageBeforeAnInstantUsesTheBrandDay() {
        Contact a = contact("A", "919900000721");
        a.setLastMessageAt(Instant.parse("2026-03-10T05:00:00Z"));
        contacts.save(a);
        Contact b = contact("B", "919900000722");
        b.setLastMessageAt(Instant.parse("2026-03-12T05:00:00Z"));
        contacts.save(b);
        contact("C", "919900000723"); // never messaged
        String cut = "2026-03-11T12:00:00Z";
        assertThat(match("lastMessageAt", "before", cut)).containsExactly(a.getId());
        assertThat(match("lastMessageAt", "after", cut)).containsExactly(b.getId());
        assertThat(match("lastMessageAt", "between", List.of("2026-03-09T12:00:00Z", "2026-03-10T12:00:00Z")))
                .containsExactly(a.getId());
        assertThat(match("lastMessageAt", "isNotSet", null)).hasSize(1);
        assertThat(match("lastMessageAt", "isSet", null)).containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    @Test
    void sourceInAndDoNotContactIs() {
        Contact a = contact("A", "919900000731");
        a.setSource(ContactSource.IMPORT);
        a.setDoNotContact(new DoNotContact(Instant.now(), DoNotContact.Reason.MANUAL, null));
        contacts.save(a);
        Contact b = contact("B", "919900000732"); // CHAT, may be contacted
        assertThat(match("source", "in", List.of("IMPORT", "MANUAL"))).containsExactly(a.getId());
        assertThat(match("source", "is", "CHAT")).containsExactly(b.getId());
        assertThat(match("doNotContact", "is", "true")).containsExactly(a.getId());
        assertThat(match("doNotContact", "is", "false")).containsExactly(b.getId());
    }

    @Test
    void limitPlusOneSignalsTooBroad() {
        ContactLabel l = label("Many");
        for (int i = 0; i < 3; i++) {
            Contact c = contact("C" + i, "91990000074" + i);
            c.setLabels(Set.of(l.getId()));
            contacts.save(c);
        }
        assertThat(channel.matching(brand.getId(), new ChannelRule("label", "has", l.getId()), 2)).hasSize(3);
        assertThat(channel.matching(brand.getId(), new ChannelRule("label", "has", l.getId()), 3)).hasSize(3);
    }

    @Test
    void accountsCarryKindAndLabel() {
        number.setLabel("Sales line");
        phoneNumbers.save(number);
        List<ChannelAccount> accounts = channel.accounts(brand.getId());
        assertThat(accounts).extracting(ChannelAccount::id).containsExactlyInAnyOrder(number.getId(), number2.getId());
        ChannelAccount sales = accounts.stream().filter(a -> a.id().equals(number.getId())).findFirst().orElseThrow();
        assertThat(sales.label()).isEqualTo("Sales line");
        assertThat(sales.address()).isEqualTo(number.getNumber());
        assertThat(sales.kind()).isEqualTo(ChannelKind.WHATSAPP_WEB);
        ChannelAccount second = accounts.stream().filter(a -> a.id().equals(number2.getId())).findFirst().orElseThrow();
        assertThat(second.label()).as("no label: the number itself").isEqualTo(number2.getNumber());
    }

    @Test
    void isBrandScoped() {
        ContactLabel l = label("VIP");
        Contact mine = contact("A", "919900000751");
        mine.setLabels(Set.of(l.getId()));
        contacts.save(mine);
        Contact theirs = contact(other, "B", "919900000752");
        theirs.setLabels(Set.of(l.getId()));
        contacts.save(theirs);
        assertThat(match("label", "has", l.getId())).containsExactly(mine.getId());
        assertThat(channel.labels(other.getId())).isEmpty();
        assertThat(channel.accounts(other.getId())).extracting(ChannelAccount::id).containsExactly(otherNumber.getId());
    }

    // --- one contact's labels and attributes (spec F C10) --------------------------------------------------------

    @Test
    void detailsNameLabelsAndAttributes() {
        ContactLabel vip = label("VIP");
        ContactLabel gold = label("Gold");
        ContactAttribute zone = attribute("Zone");
        ContactAttribute city = attribute("City");
        Contact c = contact("A", "919900000801");
        c.setLabels(Set.of(vip.getId(), gold.getId()));
        c.setAttributes(Map.of(zone.getId(), "West", city.getId(), "Pune"));
        contacts.save(c);
        ChannelContactDetails d = channel.details(brand.getId(), c.getId());
        assertThat(d.labels()).extracting("name", "color").containsExactly(
                org.assertj.core.groups.Tuple.tuple("Gold", "#ff0000"), org.assertj.core.groups.Tuple.tuple("VIP", "#ff0000"));
        assertThat(d.attributes()).containsExactly(new ChannelAttributeValue("City", "Pune"),
                new ChannelAttributeValue("Zone", "West"));
    }

    @Test
    void detailsDropUnknownIds() {
        ContactLabel vip = label("VIP");
        Contact c = contact("B", "919900000802");
        c.setLabels(Set.of(vip.getId(), "gone-label"));
        c.setAttributes(Map.of("gone-attribute", "x"));
        contacts.save(c);
        ChannelContactDetails d = channel.details(brand.getId(), c.getId());
        assertThat(d.labels()).extracting("name").containsExactly("VIP");
        assertThat(d.attributes()).isEmpty();
    }

    @Test
    void detailsOfAMissingContactAreEmpty() {
        ChannelContactDetails d = channel.details(brand.getId(), "missing");
        assertThat(d.labels()).isEmpty();
        assertThat(d.attributes()).isEmpty();
    }

    @Test
    void detailsAreBrandScoped() {
        ContactLabel vip = label("VIP");
        Contact c = contact(other, "C", "919900000803");
        c.setLabels(Set.of(vip.getId()));
        contacts.save(c);
        assertThat(channel.details(brand.getId(), c.getId()).labels()).isEmpty();
        assertThat(channel.details(other.getId(), c.getId()).labels()).isEmpty();
    }
}
