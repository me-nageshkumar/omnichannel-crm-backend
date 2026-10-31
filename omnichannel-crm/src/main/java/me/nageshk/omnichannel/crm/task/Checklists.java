package me.nageshk.omnichannel.crm.task;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.crm.task.dto.ChecklistItemRequest;
import org.bson.types.ObjectId;

/** Pure checklist rules (spec E C7, C8): the merge on save and the undone copy for the next occurrence. */
public final class Checklists {

    public static final int MAX_ITEMS = 30;
    public static final int MAX_TEXT = 200;

    private Checklists() {}

    /**
     * The checklist as sent, in order: an item whose id is already on the task (and not sent before) keeps its tick;
     * anything else is a new, undone item with a fresh id; items not sent are dropped. Null means no steps. Problems
     * go into {@code errors} ({@code checklist}, {@code checklist[i].text}); over the item cap the current list is
     * returned unchanged.
     */
    public static List<ChecklistItem> merge(List<ChecklistItem> current, List<ChecklistItemRequest> incoming,
            Map<String, String> errors) {
        if (incoming == null) return List.of();
        if (incoming.size() > MAX_ITEMS) {
            errors.put("checklist", "at most " + MAX_ITEMS + " items");
            return current;
        }
        Map<String, ChecklistItem> known = new LinkedHashMap<>();
        if (current != null) current.forEach(i -> known.put(i.getId(), i));
        Set<String> used = new HashSet<>();
        List<ChecklistItem> merged = new ArrayList<>();
        for (int n = 0; n < incoming.size(); n++) {
            ChecklistItemRequest req = incoming.get(n);
            String text = req == null || req.text() == null ? "" : req.text().trim();
            if (text.isEmpty() || text.length() > MAX_TEXT) {
                errors.put("checklist[" + n + "].text", "must be 1 to " + MAX_TEXT + " characters");
                continue;
            }
            ChecklistItem previous = req.id() == null || used.contains(req.id()) ? null : known.get(req.id());
            ChecklistItem next = new ChecklistItem();
            if (previous == null) {
                next.setId(new ObjectId().toHexString());
            } else {
                next.setId(previous.getId());
                next.setDone(previous.isDone());
                next.setDoneAt(previous.getDoneAt());
                next.setDoneBy(previous.getDoneBy());
            }
            next.setText(text);
            used.add(next.getId());
            merged.add(next);
        }
        return merged;
    }

    /** The same steps, unticked, with fresh ids (for the next occurrence of a repeating task); null for null. */
    public static List<ChecklistItem> copyUndone(List<ChecklistItem> items) {
        if (items == null) return null;
        List<ChecklistItem> out = new ArrayList<>();
        for (ChecklistItem i : items) {
            ChecklistItem c = new ChecklistItem();
            c.setId(new ObjectId().toHexString());
            c.setText(i.getText());
            out.add(c);
        }
        return out;
    }
}
