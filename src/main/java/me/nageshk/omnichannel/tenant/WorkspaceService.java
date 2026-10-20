package me.nageshk.omnichannel.tenant;

import me.nageshk.omnichannel.phone.DeviceAccessChanged;
import me.nageshk.omnichannel.phone.DeviceAccessChanged.Reason;
import org.springframework.context.ApplicationEventPublisher;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.enablement.EnableLevel;
import me.nageshk.omnichannel.enablement.Enablement;
import me.nageshk.omnichannel.api.DuplicateException;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.ValidationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Workspaces as their own customer manages them: create (with a {@code General} team, like the first
 * workspace) and rename. Names are unique within the customer. Authorities are checked by the
 * controller.
 */
@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaces;
    private final TeamRepository teams;
    private final Enablement enablement;
    private final ApplicationEventPublisher events;

    public WorkspaceService(WorkspaceRepository workspaces, TeamRepository teams, Enablement enablement,
            ApplicationEventPublisher events) {
        this.events = events;
        this.enablement = enablement;
        this.workspaces = workspaces;
        this.teams = teams;
    }

    /**
     * Inserts the workspace and its {@code General} team; if the team cannot be inserted, the
     * workspace is deleted again (Mongo here is standalone, so no transaction).
     *
     * @throws DuplicateException (field {@code name}) if the customer already has a workspace so named
     */
    public Workspace create(String customerId, String name) {
        Workspace w = new Workspace();
        w.setCustomerId(customerId);
        w.setName(requireName(name));
        Workspace workspace = insert(w);
        try {
            Team t = new Team();
            t.setCustomerId(customerId);
            t.setWorkspaceId(workspace.getId());
            t.setName(CustomerService.FIRST_TEAM);
            teams.insert(t);
        } catch (RuntimeException e) {
            try {
                workspaces.deleteById(workspace.getId());
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        return workspace;
    }

    /**
     * @throws NotFoundException if the workspace is not the customer's
     * @throws DuplicateException (field {@code name}) if another workspace of the customer has the name
     */
    public Workspace rename(String customerId, String workspaceId, String name) {
        String newName = requireName(name);
        Workspace w = workspaces.findByIdAndCustomerId(workspaceId, customerId)
                .orElseThrow(() -> new NotFoundException("workspace not found"));
        w.setName(newName);
        try {
            return workspaces.save(w);
        } catch (DuplicateKeyException e) {
            throw duplicateName();
        }
    }

    /** Disables or enables the customer's workspace at {@code level}; 404 if not the customer's. */
    public Workspace setEnabled(String customerId, String workspaceId, boolean enabled, AuthenticatedUser me,
            EnableLevel level) {
        Workspace w = workspaces.findByIdAndCustomerId(workspaceId, customerId)
                .orElseThrow(() -> new NotFoundException("workspace not found"));
        boolean before = w.getDisabled() == null;
        if (enabled) {
            if (before) return w;
            enablement.checkEnable(w.getDisabled(), level);
            w.setDisabled(null);
        } else {
            w.setDisabled(enablement.disable(w.getDisabled(), me, level));
        }
        Workspace saved = workspaces.save(w);
        if (before != enabled) enablement.record("WORKSPACE", workspaceId, before, enabled, me, level);
        if (!enabled) events.publishEvent(new DeviceAccessChanged(null, customerId, workspaceId, Reason.DISABLED));
        return saved;
    }

    private Workspace insert(Workspace w) {
        try {
            return workspaces.insert(w);
        } catch (DuplicateKeyException e) {
            throw duplicateName();
        }
    }

    private static DuplicateException duplicateName() {
        return new DuplicateException("name", "a workspace with this name already exists");
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new ValidationException("name", "must not be blank");
        return name.trim();
    }
}
