package me.nageshk.omnichannel.api;

import me.nageshk.omnichannel.events.RecentEventsStore;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@RequestMapping("/api/phone-numbers/{phoneNumberId}/bridge")
public class EventsController {

    private final RecentEventsStore store;

    public EventsController(RecentEventsStore store) {
        this.store = store;
    }

    /** Recent WhatsApp events for the current user, oldest first. Poll with {@code after=<last seq>}. */
    @GetMapping("/events")
    public List<RecentEventsStore.StoredEvent> events(
            @PathVariable String phoneNumberId,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit) {
        return store.recent(phoneNumberId, after, Math.max(1, Math.min(limit, RecentEventsStore.CAPACITY)));
    }
}
