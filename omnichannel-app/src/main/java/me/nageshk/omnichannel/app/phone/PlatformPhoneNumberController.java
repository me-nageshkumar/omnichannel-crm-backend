package me.nageshk.omnichannel.app.phone;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.enablement.EnableLevel;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.phone.PhoneNumberService;
import me.nageshk.omnichannel.service.phone.PhoneNumberView;
import me.nageshk.omnichannel.service.phone.move.MovePreview;
import me.nageshk.omnichannel.service.phone.move.PhoneNumberMoveService;
import me.nageshk.omnichannel.service.phone.move.PhoneNumberMoveView;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Every number on the platform, for platform staff: search, enable/disable at PLATFORM level, delete. */
@RestController
@RequestMapping("/api/phone-numbers")
public class PlatformPhoneNumberController {

    private final PhoneNumberService service;
    private final PhoneNumberMoveService moves;

    public PlatformPhoneNumberController(PhoneNumberService service, PhoneNumberMoveService moves) {
        this.service = service;
        this.moves = moves;
    }

    /** What moving the number to the brand would do, for the dialog that asks first. */
    @GetMapping("/{id}/move/preview")
    @PreAuthorize("hasAuthority('phone-number:manage-all')")
    public MovePreview previewMove(@PathVariable String id, @RequestParam String workspaceId) {
        return moves.preview(id, workspaceId);
    }

    /** Moves the number to another brand of its customer; a step that fails comes back unfinished, with its error. */
    @PostMapping("/{id}/move")
    @PreAuthorize("hasAuthority('phone-number:manage-all')")
    public PhoneNumberMoveView move(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id,
            @Valid @RequestBody MoveRequest body) {
        return moves.view(moves.move(id, body.workspaceId(), me));
    }

    /** The number's newest move, finished or not; 404 when it never moved. */
    @GetMapping("/{id}/move")
    @PreAuthorize("hasAuthority('phone-number:view-all')")
    public PhoneNumberMoveView lastMove(@PathVariable String id) {
        return moves.last(id).map(moves::view).orElseThrow(
                () -> new CodedException(HttpStatus.NOT_FOUND, "number_move_not_found", null,
                        "This number has no move."));
    }

    /** Runs the steps an unfinished move has not done yet. */
    @PostMapping("/{id}/move/retry")
    @PreAuthorize("hasAuthority('phone-number:manage-all')")
    public PhoneNumberMoveView retryMove(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id) {
        return moves.view(moves.retry(id, me));
    }

    /** Gives up an unfinished move that has not re-pointed anything yet; the number stays in its brand. */
    @PostMapping("/{id}/move/cancel")
    @PreAuthorize("hasAuthority('phone-number:manage-all')")
    public PhoneNumberMoveView cancelMove(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id) {
        return moves.view(moves.cancel(id, me));
    }

    public record MoveRequest(@NotBlank String workspaceId) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('phone-number:view-all')")
    public PhoneNumberView.Page search(@RequestParam(required = false) String q,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page) {
        return service.search(q, status, page);
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('phone-number:manage-all')")
    public PhoneNumberView disable(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id) {
        return service.view(service.setEnabled(service.require(id), false, me, EnableLevel.PLATFORM));
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('phone-number:manage-all')")
    public PhoneNumberView enable(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String id) {
        return service.view(service.setEnabled(service.require(id), true, me, EnableLevel.PLATFORM));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('phone-number:manage-all')")
    public void delete(@PathVariable String id) {
        service.delete(service.require(id));
    }
}
