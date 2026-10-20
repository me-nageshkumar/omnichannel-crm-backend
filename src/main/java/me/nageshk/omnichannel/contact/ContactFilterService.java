package me.nageshk.omnichannel.contact;

import java.util.List;
import me.nageshk.omnichannel.api.DuplicateException;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.ValidationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** A brand's saved contact filters. */
@Service
public class ContactFilterService {

    public static final int MAX_NAME = 60;

    private final ContactFilterRepository filters;
    private final ContactQuery query;

    public ContactFilterService(ContactFilterRepository filters, ContactQuery query) {
        this.filters = filters;
        this.query = query;
    }

    public List<FilterView> list(String workspaceId) {
        return filters.findByWorkspaceIdOrderByNameKey(workspaceId).stream().map(FilterView::of).toList();
    }

    public FilterView create(String workspaceId, String username, String name, ContactFilterSpec filter) {
        ContactFilter f = new ContactFilter();
        f.setWorkspaceId(workspaceId);
        f.setCreatedBy(username);
        return FilterView.of(save(f, name, filter));
    }

    public FilterView update(String workspaceId, String id, String name, ContactFilterSpec filter) {
        return FilterView.of(save(require(workspaceId, id), name, filter));
    }

    public void delete(String workspaceId, String id) {
        filters.delete(require(workspaceId, id));
    }

    private ContactFilter require(String workspaceId, String id) {
        return filters.findByIdAndWorkspaceId(id, workspaceId).orElseThrow(() -> new NotFoundException("filter not found"));
    }

    private ContactFilter save(ContactFilter f, String name, ContactFilterSpec filter) {
        java.util.Map<String, String> errors = new java.util.LinkedHashMap<>();
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > MAX_NAME) errors.put("name", "must be 1 to " + MAX_NAME + " characters");
        if (filter == null) errors.put("filter", "is required");
        if (!errors.isEmpty()) throw new ValidationException(errors);
        query.build(f.getWorkspaceId(), filter); // rejects conditions that cannot run
        ContactFilter clash = filters.findByWorkspaceIdAndNameKey(f.getWorkspaceId(), n.toLowerCase()).orElse(null);
        if (clash != null && !clash.getId().equals(f.getId())) throw duplicate(n);
        f.setName(n);
        f.setFilter(filter);
        try {
            return filters.save(f);
        } catch (DuplicateKeyException e) {
            throw duplicate(n);
        }
    }

    private static DuplicateException duplicate(String name) {
        return new DuplicateException("name", "\"" + name + "\" already exists in this brand");
    }
}
