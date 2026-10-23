package me.nageshk.omnichannel.app.contact;

import java.util.List;
import me.nageshk.omnichannel.service.contact.AttributeView;
import me.nageshk.omnichannel.service.contact.ContactFieldService;
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

/** A brand's contact attributes: {@code contact:view} to list, {@code contact-field:manage} to change. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/contact-attributes")
public class ContactAttributeController {

    public record AttributeRequest(String name, String description) {}

    private final ContactFieldService fields;

    public ContactAttributeController(ContactFieldService fields) {
        this.fields = fields;
    }

    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'contact:view')")
    public List<AttributeView> list(@PathVariable String workspaceId) {
        return fields.attributeViews(workspaceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has(#workspaceId, 'contact-field:manage')")
    public AttributeView create(@PathVariable String workspaceId, @RequestBody AttributeRequest req) {
        return fields.createAttribute(workspaceId, req.name(), req.description());
    }

    @PutMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'contact-field:manage')")
    public AttributeView update(@PathVariable String workspaceId, @PathVariable String id,
            @RequestBody AttributeRequest req) {
        return fields.updateAttribute(workspaceId, id, req.name(), req.description());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.has(#workspaceId, 'contact-field:manage')")
    public void delete(@PathVariable String workspaceId, @PathVariable String id) {
        fields.deleteAttribute(workspaceId, id);
    }
}
