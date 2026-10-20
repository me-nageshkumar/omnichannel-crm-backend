package me.nageshk.omnichannel.inbox;

import java.io.IOException;
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
 * The live streams of the inbox: one {@link SseEmitter} per open console, grouped by phone number. Visibility is
 * evaluated per subscriber at push time from its {@code scope} supplier, which re-reads the user's current access;
 * a supplier that throws (access lost) ends that stream.
 */
@Slf4j
@Component
public class InboxStreamBroker {

    /** One open stream and the way to compute its viewer's current scope. */
    record Subscriber(SseEmitter emitter, Supplier<InboxScope> scope) {
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

    /** Opens a stream with no timeout; it removes itself when the client goes away. */
    public SseEmitter subscribe(String phoneNumberId, Supplier<InboxScope> scope) {
        SseEmitter emitter = emitterFactory.apply(0L);
        Subscriber subscriber = new Subscriber(emitter, scope);
        // compute() is atomic per key, so it cannot interleave with remove() dropping the emptied set
        subscribers.compute(phoneNumberId, (k, set) -> {
            Set<Subscriber> target = set == null ? ConcurrentHashMap.newKeySet() : set;
            target.add(subscriber);
            return target;
        });
        Runnable remove = () -> remove(phoneNumberId, subscriber);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    /**
     * Sends the chat to every subscriber of the number, flagged {@code visible} per their scope. A subscriber who
     * may not see the chat gets only its id and phone number id with {@code visible: false} (enough to drop the
     * row), never its contents.
     */
    public void push(String phoneNumberId, WaWebChat chat, ChatView view) {
        forEach(phoneNumberId, chat, false, (s, visible) -> send(phoneNumberId, s, "chat",
                Map.of("chat", visible ? view : Map.of("id", chat.getId(), "phoneNumberId", phoneNumberId),
                        "visible", visible)));
    }

    /** Sends the message only to subscribers who can see the chat. */
    public void pushMessage(String phoneNumberId, WaWebChat chat, MessageView view) {
        forEach(phoneNumberId, chat, true, (s, visible) -> send(phoneNumberId, s, "message",
                Map.of("chatId", chat.getId(), "message", view)));
    }

    /** Sends the audit entry only to subscribers who can see the chat. */
    public void pushAction(String phoneNumberId, WaWebChat chat, ActionView view) {
        forEach(phoneNumberId, chat, true, (s, visible) -> send(phoneNumberId, s, "action",
                Map.of("chatId", chat.getId(), "action", view)));
    }

    @Scheduled(fixedRate = 25_000)
    public void ping() {
        subscribers.forEach((phoneNumberId, set) -> set.forEach(s -> {
            try {
                s.emitter().send(SseEmitter.event().comment("ping"));
            } catch (IOException | RuntimeException e) {
                drop(phoneNumberId, s);
            }
        }));
    }

    public int subscribers(String phoneNumberId) {
        Set<Subscriber> set = subscribers.get(phoneNumberId);
        return set == null ? 0 : set.size();
    }

    private interface Delivery {
        void to(Subscriber subscriber, boolean visible);
    }

    private void forEach(String phoneNumberId, WaWebChat chat, boolean onlyVisible, Delivery delivery) {
        Set<Subscriber> set = subscribers.get(phoneNumberId);
        if (set == null) {
            return;
        }
        for (Subscriber s : set) {
            boolean visible;
            try {
                visible = s.scope().get().covers(chat);
            } catch (RuntimeException e) {
                log.debug("Inbox stream for {} lost access, closing: {}", phoneNumberId, e.getMessage());
                drop(phoneNumberId, s);
                continue;
            }
            if (visible || !onlyVisible) {
                delivery.to(s, visible);
            }
        }
    }

    private void send(String phoneNumberId, Subscriber s, String event, Object payload) {
        try {
            s.emitter().send(SseEmitter.event().name(event).data(mapper.writeValueAsString(payload),
                    MediaType.APPLICATION_JSON));
        } catch (IOException | RuntimeException e) {
            log.debug("Inbox stream send failed for {}, dropping: {}", phoneNumberId, e.toString());
            drop(phoneNumberId, s);
        }
    }

    private void drop(String phoneNumberId, Subscriber s) {
        remove(phoneNumberId, s);
        try {
            s.emitter().complete();
        } catch (RuntimeException ignored) {
            // already finished
        }
    }

    private void remove(String phoneNumberId, Subscriber s) {
        subscribers.compute(phoneNumberId, (k, set) -> {
            if (set == null) {
                return null;
            }
            set.remove(s);
            return set.isEmpty() ? null : set;
        });
    }
}
