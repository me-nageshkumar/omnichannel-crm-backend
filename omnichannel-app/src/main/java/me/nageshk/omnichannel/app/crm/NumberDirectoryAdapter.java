package me.nageshk.omnichannel.app.crm;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.crm.platform.NumberDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.db.tenant.TeamRepository;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.security.access.Authority;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.stereotype.Component;

@Component
class NumberDirectoryAdapter implements NumberDirectory {

    private final PhoneNumberRepository phoneNumbers;
    private final TeamRepository teams;
    private final AccessService access;

    NumberDirectoryAdapter(PhoneNumberRepository phoneNumbers, TeamRepository teams, AccessService access) {
        this.phoneNumbers = phoneNumbers;
        this.teams = teams;
        this.access = access;
    }

    @Override
    public List<PlatformNumber> numbers(String workspaceId) {
        return phoneNumbers.findByWorkspaceIdOrderByNumber(workspaceId).stream()
                .map(n -> new PlatformNumber(n.getId(), n.getNumber(), n.getLabel())).toList();
    }

    @Override
    public Set<String> chattable(AuthenticatedUser me, String workspaceId) {
        if (access.inWorkspace(me, workspaceId).contains(Authority.INBOX_VIEW_ALL.code()))
            return phoneNumbers.findByWorkspaceIdOrderByNumber(workspaceId).stream().map(PhoneNumber::getId)
                    .collect(Collectors.toUnmodifiableSet());
        Set<String> own = teams.findByWorkspaceIdOrderByName(workspaceId).stream()
                .filter(t -> t.getMemberIds().contains(me.userId())).map(Team::getId)
                .collect(Collectors.toUnmodifiableSet());
        if (own.isEmpty()) return Set.of();
        return phoneNumbers.findByWorkspaceIdAndTeamIdIn(workspaceId, own).stream().map(PhoneNumber::getId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
