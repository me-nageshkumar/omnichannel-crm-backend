package me.nageshk.omnichannel.app.cloud;

import java.util.concurrent.CompletableFuture;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessage;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessages;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudSendResponse;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.cloud.CloudSendService;
import me.nageshk.omnichannel.service.inbox.InboxDevices;
import me.nageshk.omnichannel.service.page.MessagePageService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The Cloud API's {@code /{phone-number-id}/messages}, with our phone number id in the path: Meta's own request
 * bodies, {@code messaging_product} {@code whatsapp-web}, Meta's response shape. The body is read as a tree so an
 * unknown key is a 400 on {@code message}, not a silently dropped field.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/channel-accounts/{phoneNumberId}/messages")
public class CloudMessageController {

    private final CloudSendService send;
    private final CloudMessages cloud;
    private final MessagePageService pages;
    private final InboxDevices devices;
    private final PhoneNumberRepository numbers;

    public CloudMessageController(CloudSendService send, CloudMessages cloud, MessagePageService pages,
            InboxDevices devices, PhoneNumberRepository numbers) {
        this.send = send;
        this.cloud = cloud;
        this.pages = pages;
        this.devices = devices;
        this.numbers = numbers;
    }

    @PostMapping
    @PreAuthorize("@access.has(#workspaceId, 'inbox:send')")
    public CompletableFuture<CloudSendResponse> send(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @PathVariable String phoneNumberId, @RequestBody JsonNode body) {
        return send.send(me, workspaceId, phoneNumberId, cloud.parse(body));
    }

    /** The composer's preview: the page the message would be sent as, with inert actions and no beacon. */
    @PostMapping(value = "/preview", produces = "text/html;charset=UTF-8")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:send')")
    public String preview(@PathVariable String workspaceId, @PathVariable String phoneNumberId,
            @RequestBody JsonNode body) {
        numbers.findByIdAndWorkspaceId(phoneNumberId, workspaceId)
                .orElseThrow(() -> new NotFoundException("phone number not found"));
        CloudMessage m = send.validateForPreview(workspaceId, cloud.parse(body));
        return pages.previewHtml(m, devices.digitsOf(phoneNumberId));
    }
}
