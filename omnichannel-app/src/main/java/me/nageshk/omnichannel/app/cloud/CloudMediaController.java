package me.nageshk.omnichannel.app.cloud;

import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMediaInfo;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMediaUploadResponse;
import me.nageshk.omnichannel.common.channel.whatsapp.message.MessagingProduct;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.cloud.CloudMediaService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The Cloud API's {@code /{phone-number-id}/media} endpoints, with our phone number id in the path: upload a file for
 * a message header, read its details, fetch its bytes.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/channel-accounts/{phoneNumberId}/media")
public class CloudMediaController {

    private final CloudMediaService media;
    private final PhoneNumberRepository numbers;
    private final OmnichannelProperties.Pages pages;

    public CloudMediaController(CloudMediaService media, PhoneNumberRepository numbers, OmnichannelProperties props) {
        this.media = media;
        this.numbers = numbers;
        this.pages = props.pages();
    }

    @PostMapping
    @PreAuthorize("@access.has(#workspaceId, 'inbox:send')")
    public CloudMediaUploadResponse upload(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @PathVariable String phoneNumberId,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "messaging_product", required = false) String product,
            @RequestParam(value = "type", required = false) String type) {
        requireNumber(workspaceId, phoneNumberId);
        if (!MessagingProduct.WHATSAPP_WEB.equals(product)) {
            throw new ValidationException("messaging_product", "must be whatsapp-web");
        }
        return new CloudMediaUploadResponse(media.upload(me, workspaceId, type, file).id());
    }

    @GetMapping("/{mediaId}")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:view')")
    public CloudMediaInfo info(@PathVariable String workspaceId, @PathVariable String phoneNumberId,
            @PathVariable String mediaId) {
        requireNumber(workspaceId, phoneNumberId);
        String url = pages.baseUrl() + "/api/workspaces/" + workspaceId + "/channel-accounts/" + phoneNumberId
                + "/media/" + mediaId + "/file";
        return media.info(workspaceId, mediaId, url);
    }

    @GetMapping("/{mediaId}/file")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:view')")
    public ResponseEntity<Resource> file(@PathVariable String workspaceId, @PathVariable String phoneNumberId,
            @PathVariable String mediaId) {
        requireNumber(workspaceId, phoneNumberId);
        CloudMediaService.MediaFile f = media.open(workspaceId, mediaId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, f.mimeType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(f.size())
                .body(f.resource());
    }

    private void requireNumber(String workspaceId, String phoneNumberId) {
        numbers.findByIdAndWorkspaceId(phoneNumberId, workspaceId)
                .orElseThrow(() -> new NotFoundException("phone number not found"));
    }
}
