package me.nageshk.omnichannel.crm.deal;

import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.common.CrmPage;
import me.nageshk.omnichannel.crm.deal.dto.BoardView;
import me.nageshk.omnichannel.crm.deal.dto.CloseRequest;
import me.nageshk.omnichannel.crm.deal.dto.DealRequest;
import me.nageshk.omnichannel.crm.deal.dto.DealSearchRequest;
import me.nageshk.omnichannel.crm.deal.dto.DealView;
import me.nageshk.omnichannel.crm.deal.dto.MoveRequest;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/crm/deals")
public class CrmDealController {

    private static final String VIEW = "@access.has(#workspaceId, 'crm:view')";
    private static final String EDIT = VIEW + " and @access.has(#workspaceId, 'crm:edit')";
    private static final String DELETE = EDIT + " and @access.has(#workspaceId, 'crm:delete')";

    private final CrmAccess crmAccess;
    private final DealService deals;
    private final BoardService board;

    CrmDealController(CrmAccess crmAccess, DealService deals, BoardService board) {
        this.crmAccess = crmAccess;
        this.deals = deals;
        this.board = board;
    }

    @PostMapping("/search")
    @PreAuthorize(VIEW)
    public CrmPage<DealView> search(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody(required = false) DealSearchRequest request) {
        DealSearchRequest r = request == null
                ? new DealSearchRequest(null, null, null, null, null, null, null, null, null, null, null) : request;
        return deals.search(crmAccess.resolve(me, workspaceId), r);
    }

    @GetMapping("/board")
    @PreAuthorize(VIEW)
    public BoardView board(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestParam(required = false) String pipelineId, @RequestParam(required = false) Integer perStageLimit,
            @RequestParam(required = false) String ownerId, @RequestParam(required = false) String q) {
        return board.board(crmAccess.resolve(me, workspaceId), pipelineId, perStageLimit, ownerId, q);
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public DealView get(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        return deals.get(crmAccess.resolve(me, workspaceId), id);
    }

    @PostMapping
    @PreAuthorize(EDIT)
    public DealView create(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody DealRequest request) {
        return deals.create(crmAccess.resolve(me, workspaceId), request);
    }

    @PutMapping("/{id}")
    @PreAuthorize(EDIT)
    public DealView update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody DealRequest request) {
        return deals.update(crmAccess.resolve(me, workspaceId), id, request);
    }

    @PostMapping("/{id}/move")
    @PreAuthorize(EDIT)
    public DealView move(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody MoveRequest request) {
        return deals.move(crmAccess.resolve(me, workspaceId), id, request);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize(EDIT)
    public DealView close(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody(required = false) CloseRequest request) {
        return deals.close(crmAccess.resolve(me, workspaceId), id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(DELETE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        deals.delete(crmAccess.resolve(me, workspaceId), id);
    }
}
