package me.nageshk.omnichannel.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AuthorityCatalogTest {

    private static AuthorityDefinition def(String code, RoleLevel level) {
        AuthorityDefinition d = new AuthorityDefinition();
        d.setCode(code);
        d.setGroup(code.substring(0, code.indexOf(':')));
        d.setLevel(level);
        return d;
    }

    @Test
    void loadsOnceUntilEvicted() {
        AuthorityDefinitionRepository repo = mock(AuthorityDefinitionRepository.class);
        when(repo.findAll()).thenReturn(List.of(def("a:b", RoleLevel.BRAND)));
        AuthorityCatalog catalog = new AuthorityCatalog(repo, new RoleCache(mock(RoleRepository.class)));

        catalog.byCode("a:b");
        catalog.byCode("a:b");
        verify(repo, times(1)).findAll();

        catalog.evictAll();
        assertThat(catalog.byCode("a:b")).isPresent();
        verify(repo, times(2)).findAll();
    }

    @Test
    void levelOfUnknownIsEmpty() {
        AuthorityDefinitionRepository repo = mock(AuthorityDefinitionRepository.class);
        when(repo.findAll()).thenReturn(List.of(def("a:b", RoleLevel.COMPANY)));
        AuthorityCatalog catalog = new AuthorityCatalog(repo, new RoleCache(mock(RoleRepository.class)));

        assertThat(catalog.levelOf("a:b")).contains(RoleLevel.COMPANY);
        assertThat(catalog.levelOf("nope:x")).isEmpty();
        assertThat(catalog.levelOf(null)).isEmpty();
    }

    @Test
    void evictAlsoEvictsRoles() {
        AuthorityDefinitionRepository repo = mock(AuthorityDefinitionRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        Role r = new Role();
        r.setId("r1");
        r.setName("N");
        when(roles.findAll()).thenReturn(List.of(r));
        RoleCache roleCache = new RoleCache(roles);
        AuthorityCatalog catalog = new AuthorityCatalog(repo, roleCache);

        roleCache.byId("r1");
        verify(roles, times(1)).findAll();

        catalog.evictAll();
        roleCache.byId("r1");
        verify(roles, times(2)).findAll();
    }

    @Test
    void loadOverlappingEvictIsNotCached() {
        AuthorityDefinitionRepository repo = mock(AuthorityDefinitionRepository.class);
        AtomicReference<AuthorityCatalog> catalog = new AtomicReference<>();
        when(repo.findAll())
                .thenAnswer(inv -> {
                    // an authority edit lands (and evicts) while this load is still reading the old state
                    catalog.get().evictAll();
                    return List.of(def("a:b", RoleLevel.BRAND));
                })
                .thenReturn(List.of(def("a:b", RoleLevel.COMPANY)));
        catalog.set(new AuthorityCatalog(repo, new RoleCache(mock(RoleRepository.class))));

        assertThat(catalog.get().levelOf("a:b")).contains(RoleLevel.BRAND);
        assertThat(catalog.get().levelOf("a:b")).contains(RoleLevel.COMPANY);
        verify(repo, times(2)).findAll();
    }
}
