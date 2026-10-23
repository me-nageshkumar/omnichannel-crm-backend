package me.nageshk.omnichannel.inbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.contact.ContactRepository;
import me.nageshk.omnichannel.phone.DemoSeeder;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.CustomerRepository;
import me.nageshk.omnichannel.tenant.Team;
import me.nageshk.omnichannel.tenant.TeamRepository;
import me.nageshk.omnichannel.tenant.TeamService;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Demo chats, only with {@code omnichannel.seed.demo=true}: team "Demo Support" (demo-lead, demo-agent) when missing,
 * made the owner of the demo number,
 * and six chats with demo contacts on the demo number, 4 to 8 messages each, over the last three days. Skipped when
 * the demo customer, number, users or contacts are missing, or when the number already has a chat. Writes through the
 * repositories and {@link ChatMediaService} only, so no events or pushes fire at startup.
 */
@Component
@Order(9)
@ConditionalOnProperty(name = "omnichannel.seed.demo", havingValue = "true")
public class InboxDemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InboxDemoSeeder.class);
    private static final String BRAND = "Demo Fashion";
    private static final String TEAM = "Demo Support";
    private static final String IMAGE = "demo/new-collection.png";
    private static final String IMAGE_NAME = "new-collection.png";

    private enum Staff { AGENT, LEAD }

    /** One message of a script; {@code by} is the console sender, minutes are before now. */
    private record Line(MessageSource source, String type, String text, long minutesAgo, MessageStatus status,
            Staff by) {
        static Line in(String text, long ago) {
            return new Line(MessageSource.CONTACT, "chat", text, ago, MessageStatus.PENDING, null);
        }

        static Line image(String caption, long ago) {
            return new Line(MessageSource.CONTACT, "image", caption, ago, MessageStatus.PENDING, null);
        }

        static Line console(Staff by, String text, long ago, MessageStatus status) {
            return new Line(MessageSource.CONSOLE, "chat", text, ago, status, by);
        }

        static Line phone(String text, long ago, MessageStatus status) {
            return new Line(MessageSource.WHATSAPP_WEB, "chat", text, ago, status, null);
        }
    }

    /** One audit entry; a {@code null} {@code by} is the system. */
    private record Step(ActionType type, Staff by, Staff toUser, boolean toTeam, long minutesAgo) {
    }

    /** {@code assignee} and {@code queued} are the chat's final assignment; a closed chat has neither. */
    private record Script(String contact, Staff assignee, boolean queued, boolean closed, List<Line> lines,
            List<Step> steps) {
    }

    private static final List<Script> SCRIPTS = List.of(
            new Script("Aarav Sharma", Staff.AGENT, false, false, List.of(
                    Line.in("Hi, do you have the cotton kurta in size M?", 4200),
                    Line.console(Staff.AGENT, "Hello Aarav! Yes, the cotton kurta is available in M.", 4190,
                            MessageStatus.READ),
                    Line.in("Great, what is the price?", 4150),
                    Line.console(Staff.AGENT, "It is Rs 1,499 with free shipping.", 4140, MessageStatus.READ),
                    Line.in("Can I get it by Saturday?", 90),
                    Line.in("Also, is cash on delivery possible?", 85)),
                    List.of(new Step(ActionType.ROUTED, null, Staff.AGENT, false, 4200))),
            new Script("Ananya Patel", Staff.AGENT, false, false, List.of(
                    Line.in("Hi! I saw your new collection post.", 2000),
                    Line.console(Staff.AGENT, "Welcome Ananya! Happy to help you pick something.", 1995,
                            MessageStatus.DELIVERED),
                    Line.image("Is this jacket still available in blue?", 1990),
                    Line.console(Staff.AGENT, "Let me check the stock for you.", 1985, MessageStatus.READ),
                    Line.console(Staff.AGENT, "Yes, blue is available in M and L.", 30, MessageStatus.SENT)),
                    List.of(new Step(ActionType.ROUTED, null, null, true, 2000),
                            new Step(ActionType.ASSIGNED, Staff.LEAD, Staff.AGENT, false, 1996))),
            new Script("Rohan Malhotra", null, true, false, List.of(
                    Line.in("Hello, I placed order FS-10234 two days ago.", 600),
                    Line.in("It still shows processing. Can you check?", 598),
                    Line.in("Please reply when you can.", 590),
                    Line.in("Hello?", 45)),
                    List.of(new Step(ActionType.ROUTED, null, null, true, 600))),
            new Script("Diya Desai", Staff.LEAD, false, false, List.of(
                    Line.in("Hi, I want to return a dress I bought last week.", 3000),
                    Line.console(Staff.LEAD, "Sorry to hear that, Diya. Could you share the order number?", 2990,
                            MessageStatus.READ),
                    Line.in("It is FS-10198.", 2980),
                    Line.phone("Hi Diya, this is the store. We have raised a pickup for FS-10198.", 2970,
                            MessageStatus.DELIVERED),
                    Line.console(Staff.LEAD, "Pickup is scheduled for tomorrow between 10 and 1.", 2960,
                            MessageStatus.DELIVERED),
                    Line.in("Thank you!", 120)),
                    List.of(new Step(ActionType.ROUTED, null, Staff.LEAD, false, 3000))),
            new Script("Kabir Khanna", null, false, false, List.of(
                    Line.in("Hi, what are your store timings on Sunday?", 400),
                    Line.in("And do you have parking?", 398),
                    Line.in("Is the Bandra store open on Diwali?", 395),
                    Line.in("Anyone there?", 60)),
                    List.of()),
            new Script("Meera Das", null, false, true, List.of(
                    Line.in("Hi, my headphones stopped charging.", 4000),
                    Line.console(Staff.AGENT, "Hi Meera, sorry about that. Are you using the original cable?", 3990,
                            MessageStatus.READ),
                    Line.in("Yes, the original one.", 3980),
                    Line.console(Staff.AGENT, "Please visit our Andheri service centre; we will send a replacement slip.",
                            3970, MessageStatus.READ),
                    Line.in("Thanks, that worked out.", 3900),
                    Line.console(Staff.AGENT, "Glad to hear it! Closing this chat; message us any time.", 3890,
                            MessageStatus.READ)),
                    List.of(new Step(ActionType.ROUTED, null, Staff.AGENT, false, 4000),
                            new Step(ActionType.CLOSED, Staff.AGENT, null, false, 3885))));

    private final CustomerRepository customers;
    private final WorkspaceRepository workspaces;
    private final PhoneNumberRepository numbers;
    private final ContactRepository contacts;
    private final UserRepository users;
    private final TeamRepository teams;
    private final TeamService teamService;
    private final WaWebChatRepository chats;
    private final WaWebMessageRepository messages;
    private final WaWebChatActionRepository actions;
    private final ChatMediaService media;
    private final Clock clock;

    public InboxDemoSeeder(CustomerRepository customers, WorkspaceRepository workspaces, PhoneNumberRepository numbers,
            ContactRepository contacts, UserRepository users, TeamRepository teams, TeamService teamService,
            WaWebChatRepository chats, WaWebMessageRepository messages, WaWebChatActionRepository actions,
            ChatMediaService media, Clock clock) {
        this.customers = customers;
        this.workspaces = workspaces;
        this.numbers = numbers;
        this.contacts = contacts;
        this.users = users;
        this.teams = teams;
        this.teamService = teamService;
        this.chats = chats;
        this.messages = messages;
        this.actions = actions;
        this.media = media;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seed();
        } catch (Exception e) {
            log.warn("demo seed: inbox chats were not added: {}", e.toString());
        }
    }

    private void seed() {
        Customer customer = customers.findAll().stream().filter(c -> DemoSeeder.CUSTOMER.equals(c.getName()))
                .findFirst().orElse(null);
        if (customer == null) return;
        Workspace brand = workspaces.findByCustomerIdOrderByName(customer.getId()).stream()
                .filter(w -> BRAND.equals(w.getName())).findFirst().orElse(null);
        if (brand == null) return;
        PhoneNumber number = numbers.findByWorkspaceIdOrderByNumber(brand.getId()).stream()
                .filter(n -> DemoSeeder.NUMBER.equals(n.getNumber())).findFirst().orElse(null);
        if (number == null) return;
        if (!chats.findIdsByPhoneNumberId(number.getId()).isEmpty()) {
            ownByExistingTeam(brand, number);
            return;
        }

        User agent = users.findByUsername("demo-agent").orElse(null);
        User lead = users.findByUsername("demo-lead").orElse(null);
        Map<String, Contact> byName = contacts.findAll().stream().filter(c -> brand.getId().equals(c.getWorkspaceId()))
                .collect(Collectors.toMap(Contact::getName, Function.identity(), (a, b) -> a));
        if (agent == null || lead == null
                || SCRIPTS.stream().anyMatch(s -> !byName.containsKey(s.contact()))) {
            log.warn("demo seed: demo users or contacts are missing; no inbox chats added to +{}", DemoSeeder.NUMBER);
            return;
        }

        Map<Staff, User> staff = Map.of(Staff.AGENT, agent, Staff.LEAD, lead);
        Team team = team(customer, brand, staff);
        if (number.getTeamId() == null) {
            // the demo number is worked by Demo Support (design §3.1), so demo-agent sees it in the inbox
            number.setTeamId(team.getId());
            number = numbers.save(number);
        }
        byte[] png = imageBytes();
        Instant now = clock.instant();
        for (Script script : SCRIPTS) chat(script, customer, brand, number, team, staff, byName.get(script.contact()), png, now);
        log.warn("demo seed: {} chats added to +{}", SCRIPTS.size(), DemoSeeder.NUMBER);
    }

    /**
     * A demo database seeded before numbers had owning teams: the demo number goes to Demo Support if that team
     * exists, so demo-agent sees it in the inbox. Its chats are not touched.
     */
    private void ownByExistingTeam(Workspace brand, PhoneNumber number) {
        if (number.getTeamId() != null) return;
        teams.findByWorkspaceIdOrderByName(brand.getId()).stream().filter(t -> TEAM.equals(t.getName())).findFirst()
                .ifPresent(team -> {
                    number.setTeamId(team.getId());
                    numbers.save(number);
                    log.warn("demo seed: +{} is now worked by {}", DemoSeeder.NUMBER, TEAM);
                });
    }

    private Team team(Customer customer, Workspace brand, Map<Staff, User> staff) {
        Team existing = teams.findByWorkspaceIdOrderByName(brand.getId()).stream()
                .filter(t -> TEAM.equals(t.getName())).findFirst().orElse(null);
        if (existing != null) return existing;
        Team created = teamService.create(customer.getId(), brand.getId(), TEAM).team();
        return teamService.update(customer.getId(), brand.getId(), created.getId(), TEAM,
                List.of(staff.get(Staff.LEAD).getId(), staff.get(Staff.AGENT).getId())).team();
    }

    private void chat(Script script, Customer customer, Workspace brand, PhoneNumber number, Team team,
            Map<Staff, User> staff, Contact contact, byte[] png, Instant now) {
        WaWebChat chat = new WaWebChat();
        chat.setCustomerId(customer.getId());
        chat.setWorkspaceId(brand.getId());
        chat.setPhoneNumberId(number.getId());
        chat.setRemoteId(contact.getPhone() != null ? contact.getPhone() + "@c.us" : contact.getWaId());
        chat.setContactId(contact.getId());
        chat.setStatus(script.closed() ? ChatStatus.CLOSED : ChatStatus.OPEN);
        if (script.assignee() != null) chat.setAssignedUserId(staff.get(script.assignee()).getId());
        if (script.queued()) chat.setAssignedTeamId(team.getId());
        chat = chats.save(chat);

        List<WaWebMessage> saved = new ArrayList<>();
        int n = 0;
        for (Line line : script.lines()) saved.add(message(chat, line, ++n, staff, png, now));
        WaWebMessage last = saved.get(saved.size() - 1);
        int lastConsole = -1;
        Instant lastIn = null;
        Instant lastOut = null;
        for (int i = 0; i < saved.size(); i++) {
            WaWebMessage m = saved.get(i);
            if (m.getSource() == MessageSource.CONSOLE) lastConsole = i;
            if (m.getDirection() == MessageDirection.IN) lastIn = m.getTimestamp();
            else lastOut = m.getTimestamp();
        }
        int unread = 0;
        for (WaWebMessage m : saved.subList(lastConsole + 1, saved.size())) {
            if (m.getDirection() == MessageDirection.IN) unread++;
        }
        chat.setUnreadCount(unread);
        chat.setLastMessageAt(last.getTimestamp());
        chat.setLastMessage(new WaWebChat.LastMessage(last.getDirection(), last.getType(),
                InboxIngestService.preview(last.getType(), last.getBody(), null), last.getStatus()));
        chat.setLastInboundAt(lastIn);
        chat.setLastOutboundAt(lastOut);

        Instant assignedAt = null;
        for (Step step : script.steps()) {
            WaWebChatAction a = action(chat, step, staff, team, now);
            if (step.type() != ActionType.CLOSED) assignedAt = a.getAt();
        }
        chat.setAssignedAt(assignedAt);
        chats.save(chat);
    }

    private WaWebMessage message(WaWebChat chat, Line line, int n, Map<Staff, User> staff, byte[] png, Instant now) {
        boolean inbound = line.source() == MessageSource.CONTACT;
        Instant at = now.minus(Duration.ofMinutes(line.minutesAgo()));
        WaWebMessage m = new WaWebMessage();
        m.setCustomerId(chat.getCustomerId());
        m.setWorkspaceId(chat.getWorkspaceId());
        m.setPhoneNumberId(chat.getPhoneNumberId());
        m.setChatId(chat.getId());
        m.setWaMessageId("demo_" + chat.getRemoteId() + "_" + n);
        m.setDirection(inbound ? MessageDirection.IN : MessageDirection.OUT);
        m.setSource(line.source());
        m.setType(line.type());
        m.setBody(line.text());
        m.setTimestamp(at);
        m.setStatus(line.status());
        WaWebMessage.StatusTimes times = new WaWebMessage.StatusTimes();
        if (!inbound) {
            m.setAck(switch (line.status()) {
                case SENT -> 1;
                case DELIVERED -> 2;
                default -> 3;
            });
            times.setSentAt(at.plusSeconds(1));
            if (line.status().isAtLeast(MessageStatus.DELIVERED)) times.setDeliveredAt(at.plusSeconds(3));
            if (line.status().isAtLeast(MessageStatus.READ)) times.setReadAt(at.plusSeconds(90));
        }
        m.setStatusTimes(times);
        if (line.source() == MessageSource.CONSOLE) {
            User by = staff.get(line.by());
            m.setSentByUserId(by.getId());
            m.setSentByName(by.getName());
        }
        m = messages.save(m);
        if ("image".equals(line.type())) {
            String mediaId = media.store(chat.getWorkspaceId(), chat.getPhoneNumberId(), m.getId(), "image/png",
                    IMAGE_NAME, png);
            m.setMedia(new WaWebMessage.Media("image/png", IMAGE_NAME, (long) png.length, mediaId, at.plusSeconds(5)));
            m = messages.save(m);
        }
        return m;
    }

    private WaWebChatAction action(WaWebChat chat, Step step, Map<Staff, User> staff, Team team, Instant now) {
        WaWebChatAction a = new WaWebChatAction();
        a.setCustomerId(chat.getCustomerId());
        a.setWorkspaceId(chat.getWorkspaceId());
        a.setChatId(chat.getId());
        a.setType(step.type());
        a.setAt(now.minus(Duration.ofMinutes(step.minutesAgo())).plusSeconds(1));
        if (step.by() != null) {
            a.setByUserId(staff.get(step.by()).getId());
            a.setByName(staff.get(step.by()).getName());
        }
        if (step.toUser() != null) {
            a.setToUserId(staff.get(step.toUser()).getId());
            a.setToUserName(staff.get(step.toUser()).getName());
        }
        if (step.toTeam()) {
            a.setToTeamId(team.getId());
            a.setToTeamName(team.getName());
        }
        return actions.save(a);
    }

    private static byte[] imageBytes() {
        try (InputStream in = new ClassPathResource(IMAGE).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
