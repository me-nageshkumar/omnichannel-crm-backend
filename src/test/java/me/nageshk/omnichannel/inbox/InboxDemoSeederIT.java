package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.contact.ContactRepository;
import me.nageshk.omnichannel.contact.ContactDemoSeeder;
import me.nageshk.omnichannel.phone.DemoSeeder;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.tenant.Team;
import me.nageshk.omnichannel.tenant.TeamRepository;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

@SpringBootTest(properties = "omnichannel.seed.demo=true")
class InboxDemoSeederIT {

    @Autowired DemoSeeder demoSeeder;
    @Autowired ContactDemoSeeder contactSeeder;
    @Autowired InboxDemoSeeder seeder;
    @Autowired TestUsers testUsers;
    @Autowired InboxCleanup cleanup;
    @Autowired PhoneNumberRepository numbers;
    @Autowired WaWebChatRepository chats;
    @Autowired WaWebMessageRepository messages;
    @Autowired WaWebChatActionRepository actions;
    @Autowired TeamRepository teams;
    @Autowired UserRepository users;
    @Autowired ContactRepository contacts;
    @Autowired ChatMediaService media;

    @BeforeEach
    void clean() {
        wipe();
    }

    @AfterEach
    void cleanAfter() {
        wipe();
    }

    private void wipe() {
        for (PhoneNumber n : numbers.findAll()) cleanup.deleteForNumber(n.getId());
        actions.deleteAll();
        messages.deleteAll();
        chats.deleteAll();
        testUsers.deleteAll();
    }

    private List<Team> supportTeams() {
        return teams.findAll().stream().filter(t -> t.getName().equals("Demo Support")).toList();
    }

    private void seedAll() {
        demoSeeder.run(null);
        contactSeeder.run(null);
        seeder.run(null);
    }

    @Test
    void seedsSixChatsOnDemoNumber() throws Exception {
        seedAll();

        PhoneNumber number = numbers.findAll().stream().filter(n -> DemoSeeder.NUMBER.equals(n.getNumber()))
                .findFirst().orElseThrow();
        User agent = users.findByUsername("demo-agent").orElseThrow();
        User lead = users.findByUsername("demo-lead").orElseThrow();
        Team team = teams.findByWorkspaceIdOrderByName(number.getWorkspaceId()).stream()
                .filter(t -> t.getName().equals("Demo Support")).findFirst().orElseThrow();
        assertThat(team.getMemberIds()).containsExactlyInAnyOrder(lead.getId(), agent.getId());

        List<WaWebChat> all = chats.findAll();
        assertThat(all).hasSize(6).allMatch(c -> c.getPhoneNumberId().equals(number.getId())
                && c.getWorkspaceId().equals(number.getWorkspaceId())
                && c.getCustomerId().equals(number.getCustomerId()) && c.getContactId() != null);
        assertThat(all.stream().filter(c -> agent.getId().equals(c.getAssignedUserId()))).hasSize(2);
        assertThat(all.stream().filter(c -> lead.getId().equals(c.getAssignedUserId()))).hasSize(1);
        assertThat(all.stream().filter(c -> c.getAssignedUserId() == null && team.getId().equals(c.getAssignedTeamId())
                && c.getStatus() == ChatStatus.OPEN)).hasSize(1);
        assertThat(all.stream().filter(c -> c.getAssignedUserId() == null && c.getAssignedTeamId() == null
                && c.getStatus() == ChatStatus.OPEN)).hasSize(1);
        assertThat(all.stream().filter(c -> c.getStatus() == ChatStatus.CLOSED))
                .hasSize(1).allMatch(c -> c.getAssignedUserId() == null && c.getAssignedTeamId() == null);

        Map<String, Contact> contactById = contacts.findAll().stream()
                .collect(Collectors.toMap(Contact::getId, Function.identity()));
        assertThat(all.stream().map(c -> contactById.get(c.getContactId()).getName()))
                .containsExactlyInAnyOrder("Aarav Sharma", "Ananya Patel", "Rohan Malhotra", "Diya Desai",
                        "Kabir Khanna", "Meera Das");

        Instant now = Instant.now();
        List<WaWebMessage> images = new ArrayList<>();
        int whatsappWeb = 0;
        for (WaWebChat chat : all) {
            Contact contact = contactById.get(chat.getContactId());
            assertThat(chat.getRemoteId())
                    .isEqualTo(contact.getPhone() != null ? contact.getPhone() + "@c.us" : contact.getWaId());
            List<WaWebMessage> thread = messages.findAll().stream().filter(m -> chat.getId().equals(m.getChatId()))
                    .sorted((a, b) -> a.getTimestamp().compareTo(b.getTimestamp())).toList();
            assertThat(thread).hasSizeBetween(4, 8);
            for (int i = 0; i < thread.size(); i++) {
                WaWebMessage m = thread.get(i);
                assertThat(m.getWaMessageId()).isEqualTo("demo_" + chat.getRemoteId() + "_" + (i + 1));
                assertThat(m.getTimestamp()).isBefore(now.plusSeconds(60)).isAfter(now.minus(Duration.ofDays(3).plusHours(1)));
                if (m.getSource() == MessageSource.CONSOLE) {
                    assertThat(m.getDirection()).isEqualTo(MessageDirection.OUT);
                    assertThat(m.getSentByUserId()).isIn(agent.getId(), lead.getId());
                    assertThat(m.getSentByName()).isNotBlank();
                    assertThat(m.getStatus()).isIn(MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.READ);
                    assertThat(m.getStatusTimes().getSentAt()).isNotNull();
                    assertThat(m.getStatusTimes().getDeliveredAt() != null).isEqualTo(m.getStatus().isAtLeast(MessageStatus.DELIVERED));
                    assertThat(m.getStatusTimes().getReadAt() != null).isEqualTo(m.getStatus() == MessageStatus.READ);
                } else if (m.getSource() == MessageSource.WHATSAPP_WEB) {
                    whatsappWeb++;
                    assertThat(m.getDirection()).isEqualTo(MessageDirection.OUT);
                    assertThat(m.getSentByUserId()).isNull();
                } else {
                    assertThat(m.getDirection()).isEqualTo(MessageDirection.IN);
                }
                if ("image".equals(m.getType())) images.add(m);
            }

            WaWebMessage last = thread.get(thread.size() - 1);
            assertThat(chat.getLastMessageAt()).isEqualTo(last.getTimestamp());
            assertThat(chat.getLastMessage().direction()).isEqualTo(last.getDirection());
            assertThat(chat.getLastMessage().type()).isEqualTo(last.getType());
            assertThat(chat.getLastMessage().text()).isNotBlank();
            int lastConsole = -1;
            for (int i = 0; i < thread.size(); i++) if (thread.get(i).getSource() == MessageSource.CONSOLE) lastConsole = i;
            long unread = thread.subList(lastConsole + 1, thread.size()).stream()
                    .filter(m -> m.getDirection() == MessageDirection.IN).count();
            assertThat(chat.getUnreadCount()).isEqualTo((int) unread);
            assertThat(chat.getLastInboundAt()).isEqualTo(thread.stream().filter(m -> m.getDirection() == MessageDirection.IN)
                    .map(WaWebMessage::getTimestamp).max(Instant::compareTo).orElse(null));
            assertThat(chat.getLastOutboundAt()).isEqualTo(thread.stream().filter(m -> m.getDirection() == MessageDirection.OUT)
                    .map(WaWebMessage::getTimestamp).max(Instant::compareTo).orElse(null));

            List<WaWebChatAction> trail = actions.findAll().stream().filter(a -> chat.getId().equals(a.getChatId()))
                    .sorted((a, b) -> a.getAt().compareTo(b.getAt())).toList();
            if (chat.getStatus() == ChatStatus.CLOSED) {
                assertThat(trail).extracting(WaWebChatAction::getType).last().isEqualTo(ActionType.CLOSED);
                assertThat(trail.get(trail.size() - 1).getByName()).isNotBlank();
            } else if (chat.getAssignedUserId() == null && chat.getAssignedTeamId() == null) {
                assertThat(trail).isEmpty();
            } else {
                assertThat(trail).isNotEmpty();
                assertThat(trail.get(0).getType()).isEqualTo(ActionType.ROUTED);
                assertThat(trail.get(0).getByUserId()).isNull();
                WaWebChatAction end = trail.get(trail.size() - 1);
                assertThat(end.getToUserId()).isEqualTo(chat.getAssignedUserId());
                assertThat(end.getToTeamId()).isEqualTo(chat.getAssignedTeamId());
                assertThat(end.getAt()).isEqualTo(chat.getAssignedAt());
                if (chat.getAssignedUserId() != null) assertThat(end.getToUserName()).isNotBlank();
                if (chat.getAssignedTeamId() != null) assertThat(end.getToTeamName()).isEqualTo("Demo Support");
            }
        }
        assertThat(whatsappWeb).isEqualTo(1);

        assertThat(images).hasSize(1);
        WaWebMessage image = images.get(0);
        assertThat(image.getDirection()).isEqualTo(MessageDirection.IN);
        assertThat(image.getMedia().mimetype()).isEqualTo("image/png");
        assertThat(image.getMedia().filename()).isEqualTo("new-collection.png");
        assertThat(image.getMedia().mediaId()).isNotNull();
        assertThat(image.getMedia().downloadedAt()).isNotNull();
        byte[] expected;
        try (InputStream in = new ClassPathResource("demo/new-collection.png").getInputStream()) {
            expected = in.readAllBytes();
        }
        assertThat(image.getMedia().size()).isEqualTo(expected.length);
        ChatMediaService.MediaFile file = media.open(number.getWorkspaceId(), image.getMedia().mediaId());
        try (InputStream in = file.resource().getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(expected);
        }
    }

    @Test
    void secondRunIsNoop() {
        seedAll();
        seeder.run(null);
        assertThat(chats.count()).isEqualTo(6);
        assertThat(supportTeams()).hasSize(1);
        long total = messages.count();
        seeder.run(null);
        assertThat(messages.count()).isEqualTo(total);
        assertThat(actions.count()).isGreaterThan(6);
    }

    @Test
    void existingTeamKeepsItsMembers() {
        demoSeeder.run(null);
        contactSeeder.run(null);
        PhoneNumber number = numbers.findAll().stream().filter(n -> DemoSeeder.NUMBER.equals(n.getNumber()))
                .findFirst().orElseThrow();
        Team mine = new Team();
        mine.setCustomerId(number.getCustomerId());
        mine.setWorkspaceId(number.getWorkspaceId());
        mine.setName("Demo Support");
        teams.save(mine);

        seeder.run(null);

        assertThat(supportTeams()).hasSize(1).allMatch(t -> t.getMemberIds().isEmpty());
        assertThat(chats.count()).isEqualTo(6);
    }

    @Test
    void skipsWhenNumberAlreadyHasAChat() {
        demoSeeder.run(null);
        contactSeeder.run(null);
        PhoneNumber number = numbers.findAll().stream().filter(n -> DemoSeeder.NUMBER.equals(n.getNumber()))
                .findFirst().orElseThrow();
        WaWebChat mine = new WaWebChat();
        mine.setCustomerId(number.getCustomerId());
        mine.setWorkspaceId(number.getWorkspaceId());
        mine.setPhoneNumberId(number.getId());
        mine.setRemoteId("911111111111@c.us");
        mine.setStatus(ChatStatus.OPEN);
        chats.save(mine);

        seeder.run(null);

        assertThat(chats.count()).isEqualTo(1);
        assertThat(supportTeams()).isEmpty();
        assertThat(messages.count()).isZero();
    }

    @Test
    void skipsWithoutDemoCustomer() {
        seeder.run(null);
        assertThat(chats.count()).isZero();
        assertThat(supportTeams()).isEmpty();
    }
}
