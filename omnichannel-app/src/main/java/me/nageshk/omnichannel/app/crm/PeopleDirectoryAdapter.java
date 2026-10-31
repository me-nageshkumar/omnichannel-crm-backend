package me.nageshk.omnichannel.app.crm;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.crm.platform.PeopleDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformPerson;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.db.tenant.TeamRepository;
import me.nageshk.omnichannel.db.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.db.user.BrandDisable;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.security.access.AccessService;
import org.springframework.stereotype.Component;

@Component
class PeopleDirectoryAdapter implements PeopleDirectory {

    private final TeamRepository teams;
    private final UserRepository users;
    private final WorkspaceRepository workspaces;
    private final AccessService access;

    PeopleDirectoryAdapter(TeamRepository teams, UserRepository users, WorkspaceRepository workspaces,
            AccessService access) {
        this.teams = teams;
        this.users = users;
        this.workspaces = workspaces;
        this.access = access;
    }

    @Override
    public Set<String> teamIdsOf(String workspaceId, String userId) {
        return teams.findByWorkspaceIdOrderByName(workspaceId).stream()
                .filter(t -> t.getMemberIds().contains(userId)).map(Team::getId)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean isBrandUser(String workspaceId, String userId) {
        Optional<User> user = users.findById(userId);
        if (user.isEmpty()) return false;
        User u = user.get();
        if (!u.isActive() || u.getDisabled() != null) return false;
        if (u.getBrandDisables().stream().map(BrandDisable::workspaceId).anyMatch(workspaceId::equals)) return false;
        if (u.getWorkspaceRoles().stream().map(WorkspaceRole::workspaceId).anyMatch(workspaceId::equals)) return true;
        if (u.getRoleId() == null || u.getCustomerId() == null) return false;
        return workspaces.findById(workspaceId).map(w -> u.getCustomerId().equals(w.getCustomerId())).orElse(false);
    }

    @Override
    public Map<String, PlatformPerson> people(Collection<String> userIds) {
        return users.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, PeopleDirectoryAdapter::of));
    }

    @Override
    public Optional<PlatformPerson> byUsername(String username) {
        return users.findByUsername(username).map(PeopleDirectoryAdapter::of);
    }

    @Override
    public Optional<Set<String>> authoritiesIn(String workspaceId, String userId) {
        if (!isBrandUser(workspaceId, userId)) return Optional.empty();
        try {
            return Optional.of(access.inWorkspace(access.load(users.findById(userId).orElseThrow()), workspaceId));
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }

    private static PlatformPerson of(User u) {
        return new PlatformPerson(u.getId(), u.getName(), u.getUsername());
    }
}
