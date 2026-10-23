package me.nageshk.omnichannel.inbox;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * Fans inbox events out to the open consoles of a workspace (design §3.7). Each subscriber is a viewer with a scope
 * and the numbers they are looking at, re-resolved from their token on every push and ping so a user who is
 * disabled, logged out, demoted or moved between teams stops receiving at once. Whether a chat is the viewer's to
 * hear about is decided per chat from the scope alone (no query): a chat on a number their team owns, or one they
 * hold or that sits in their team's queue on any other number.
 */
@Slf4j
@Component
public class InboxStreamBroker {

    /** How many chat ids a subscriber remembers having shown, so it can be told when one of them leaves. */
    static final int REMEMBERED_CHATS = 500;

    /**
     * What one open console may see right now: its chat scope and the numbers it selected ({@code requested} empty
     * means every number it may open).
     */
    public record Viewer(InboxScope scope, Set<String> requested) {
        /** Whether events about {@code chat} are this viewer's to receive. */
        boolean sees(WaWebChat chat) {
            String number = chat.getPhoneNumberId();
            if (!requested.isEmpty() && !requested.contains(number)) return false;
            return scope.level() == InboxScope.Level.ALL || scope.ownedNumberIds().contains(number)
                    || scope.covers(chat);
        }
    }

    static final class Subscriber {
        final SseEmitter emitter;
        final Supplier<Viewer> viewer;
        /** Chats this console has been shown, newest last; bounded, so a long-lived console cannot grow it. */
        private final LinkedHashSet<String> shown = new LinkedHashSet<>();

        Subscriber(SseEmitter emitter, Supplier<Viewer> viewer) {
            this.emitter = emitter;
            this.viewer = viewer;
        }

        synchronized void remember(String chatId) {
            shown.remove(chatId);
            shown.add(chatId);
            if (shown.size() > REMEMBERED_CHATS) shown.iterator().remove();
        }

        synchronized boolean forget(String chatId) {
            return shown.remove(chatId);
        }
    }

    private final Map<String, Set<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final LongFunction<SseEmitter> emitterFactory;

    @Autowired
    public InboxStreamBroker(ObjectMapper mapper) {
        this(mapper, SseEmitter::new);
    }

    InboxStreamBroker(ObjectMapper mapper, LongFunction<SseEmitter> emitterFactory) {
        this.mapper = mapper;
        this.emitterFactory = emitterFactory;
    }

    public SseEmitter subscribe(String workspaceId, Supplier<Viewer> viewer) {
        SseEmitter emitter = emitterFactory.apply(0L);
        Subscriber subscriber = new Subscriber(emitter, viewer);
        // compute() is atomic per key, so it cannot interleave with remove() dropping the emptied set
        subscribers.compute(workspaceId, (k, set) -> {
            Set<Subscriber> target = set == null ? ConcurrentHashMap.newKeySet() : set;
            target.add(subscriber);
            return target;
        });
        Runnable remove = () -> remove(workspaceId, subscriber);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    /**
     * A chat changed: viewers it belongs to hear about it, with {@code visible:false} when it is on their number
     * but outside their scope, or when it was shown to them before and is no longer theirs at all.
     */
    public void push(String workspaceId, WaWebChat chat, ChatView view) {
        forEach(workspaceId, (s, viewer) -> {
            boolean sees = viewer.sees(chat);
            boolean visible = sees && viewer.scope().covers(chat);
            if (visible) {
                s.remember(chat.getId());
            } else if (!sees && !s.forget(chat.getId())) {
                return;
            } else {
                s.forget(chat.getId());
            }
            send(workspaceId, s, "chat", Map.of("chat",
                    visible ? view : Map.of("id", chat.getId(), "phoneNumberId", chat.getPhoneNumberId()),
                    "visible", visible));
        });
    }

    public void pushMessage(String workspaceId, WaWebChat chat, MessageView view) {
        forEach(workspaceId, (s, viewer) -> {
            if (!viewer.sees(chat) || !viewer.scope().covers(chat)) return;
            s.remember(chat.getId());
            send(workspaceId, s, "message", Map.of("chatId", chat.getId(), "message", view));
        });
    }

    public void pushAction(String workspaceId, WaWebChat chat, ActionView view) {
        forEach(workspaceId, (s, viewer) -> {
            if (!viewer.sees(chat) || !viewer.scope().covers(chat)) return;
            s.remember(chat.getId());
            send(workspaceId, s, "action", Map.of("chatId", chat.getId(), "action", view));
        });
    }

    @Scheduled(fixedRate = 25_000)
    public void ping() {
        subscribers.forEach((workspaceId, set) -> set.forEach(s -> {
            try {
                s.viewer.get();
            } catch (RuntimeException e) {
                log.debug("Inbox stream in {} lost access, closing: {}", workspaceId, e.getMessage());
                drop(workspaceId, s);
                return;
            }
            try {
                s.emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | RuntimeException e) {
                drop(workspaceId, s);
            }
        }));
    }

    public int subscribers(String workspaceId) {
        Set<Subscriber> set = subscribers.get(workspaceId);
        return set == null ? 0 : set.size();
    }

    private interface Delivery {
        void to(Subscriber subscriber, Viewer viewer);
    }

    private void forEach(String workspaceId, Delivery delivery) {
        Set<Subscriber> set = subscribers.get(workspaceId);
        if (set == null) {
            return;
        }
        for (Subscriber s : set) {
            Viewer viewer;
            try {
                viewer = s.viewer.get();
            } catch (RuntimeException e) {
                log.debug("Inbox stream in {} lost access, closing: {}", workspaceId, e.getMessage());
                drop(workspaceId, s);
                continue;
            }
            delivery.to(s, viewer);
        }
    }

    private void send(String workspaceId, Subscriber s, String event, Object payload) {
        try {
            s.emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(payload),
                    MediaType.APPLICATION_JSON));
        } catch (IOException | RuntimeException e) {
            log.debug("Inbox stream send failed in {}, dropping: {}", workspaceId, e.toString());
            drop(workspaceId, s);
        }
    }

    private void drop(String workspaceId, Subscriber s) {
        remove(workspaceId, s);
        try {
            s.emitter.complete();
        } catch (RuntimeException ignored) {
            // already finished
        }
    }

    private void remove(String workspaceId, Subscriber s) {
        subscribers.compute(workspaceId, (k, set) -> {
            if (set == null) {
                return null;
            }
            set.remove(s);
            return set.isEmpty() ? null : set;
        });
    }
}
