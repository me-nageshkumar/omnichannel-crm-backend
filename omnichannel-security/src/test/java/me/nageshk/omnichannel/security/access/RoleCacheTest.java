package me.nageshk.omnichannel.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.access.RoleRepository;
import org.junit.jupiter.api.Test;

class RoleCacheTest {

    private static Role role(Set<String> authorities) {
        Role r = new Role();
        r.setId("r1");
        r.setName(BuiltInRoles.WORKSPACE_MANAGER);
        r.setLevel(RoleLevel.BRAND);
        r.setAuthorities(authorities);
        return r;
    }

    @Test
    void evictDuringLoadDoesNotLeaveTheStaleSnapshotCached() {
        RoleRepository repo = mock(RoleRepository.class);
        AtomicReference<RoleCache> cache = new AtomicReference<>();
        Role stale = role(Set.of("user:manage"));
        Role edited = role(Set.of());
        when(repo.findAll())
                .thenAnswer(inv -> {
                    // a role edit lands (and evicts) while this load is still reading the old state
                    cache.get().evictAll();
                    return List.of(stale);
                })
                .thenReturn(List.of(edited));
        cache.set(new RoleCache(repo));

        assertThat(cache.get().byId("r1").orElseThrow().getAuthorities()).contains("user:manage");
        assertThat(cache.get().byId("r1").orElseThrow().getAuthorities()).isEmpty();
        assertThat(cache.get().byName(BuiltInRoles.WORKSPACE_MANAGER).orElseThrow().getAuthorities()).isEmpty();
        verify(repo, times(2)).findAll();
    }

    @Test
    void loadsOnceUntilEvicted() {
        RoleRepository repo = mock(RoleRepository.class);
        when(repo.findAll()).thenReturn(List.of(role(Set.of())));
        RoleCache cache = new RoleCache(repo);

        cache.byId("r1");
        cache.byName(BuiltInRoles.WORKSPACE_MANAGER);
        verify(repo, times(1)).findAll();

        cache.evictAll();
        cache.byId("r1");
        verify(repo, times(2)).findAll();
    }
}
