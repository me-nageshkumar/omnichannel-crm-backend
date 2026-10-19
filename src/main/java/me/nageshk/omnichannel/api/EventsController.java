package me.nageshk.omnichannel.api;

import me.nageshk.omnichannel.events.RecentEventsStore;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class EventsController {

    private final RecentEventsStore store;

    public EventsController(RecentEventsStore store) {
        this.store = store;
    }

    /** Recent WhatsApp events for the current user, oldest first. Poll with {@code after=<last seq>}. */
    @GetMapping("/events")
    public List<RecentEventsStore.StoredEvent> events(
            Authentication auth,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit) {
        return store.recent(auth.getName(), after, Math.max(1, Math.min(limit, RecentEventsStore.CAPACITY)));
    }
}
