package me.nageshk.omnichannel.contact;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A brand's contact labels: {@code contact:view} to list, {@code contact-field:manage} to change. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/contact-labels")
public class ContactLabelController {

    public record LabelRequest(String name, String color, String description) {}

    private final ContactFieldService fields;

    public ContactLabelController(ContactFieldService fields) {
        this.fields = fields;
    }

    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'contact:view')")
    public List<LabelView> list(@PathVariable String workspaceId) {
        return fields.labelViews(workspaceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has(#workspaceId, 'contact-field:manage')")
    public LabelView create(@PathVariable String workspaceId, @RequestBody LabelRequest req) {
        return fields.createLabel(workspaceId, req.name(), req.color(), req.description());
    }

    @PutMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'contact-field:manage')")
    public LabelView update(@PathVariable String workspaceId, @PathVariable String id, @RequestBody LabelRequest req) {
        return fields.updateLabel(workspaceId, id, req.name(), req.color(), req.description());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.has(#workspaceId, 'contact-field:manage')")
    public void delete(@PathVariable String workspaceId, @PathVariable String id) {
        fields.deleteLabel(workspaceId, id);
    }
}
