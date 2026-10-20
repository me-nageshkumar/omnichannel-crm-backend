package me.nageshk.omnichannel.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.access.AuthorityCheckScanner.CheckKind;
import org.junit.jupiter.api.Test;

class AuthorityCheckScannerTest {

    @Test
    void hasAuthorityIsGlobal() {
        assertThat(AuthorityCheckScanner.parse("hasAuthority('customer:view')"))
                .isEqualTo(Map.of("customer:view", Set.of(CheckKind.GLOBAL)));
    }

    @Test
    void hasAnyAuthorityReadsEveryCode() {
        assertThat(AuthorityCheckScanner.parse("hasAnyAuthority('a:b', 'c-d:e')"))
                .isEqualTo(Map.of("a:b", Set.of(CheckKind.GLOBAL), "c-d:e", Set.of(CheckKind.GLOBAL)));
    }

    @Test
    void accessHasIsBrand() {
        assertThat(AuthorityCheckScanner.parse("@access.has(#workspaceId, 'user:manage')"))
                .isEqualTo(Map.of("user:manage", Set.of(CheckKind.BRAND)));
    }

    @Test
    void orCombinesBothCodes() {
        assertThat(AuthorityCheckScanner.parse(
                        "hasAuthority('customer:manage') or hasAuthority('customer:create')"))
                .isEqualTo(Map.of("customer:manage", Set.of(CheckKind.GLOBAL),
                        "customer:create", Set.of(CheckKind.GLOBAL)));
    }

    @Test
    void markerIsIgnored() {
        assertThat(AuthorityCheckScanner.parse("hasAuthority('SUPER_ADMIN')")).isEmpty();
    }

    @Test
    void sameCodeCanBeBothKinds() {
        assertThat(AuthorityCheckScanner.parse("@access.has(#w, 'x:y') or hasAuthority('x:y')"))
                .isEqualTo(Map.of("x:y", Set.of(CheckKind.GLOBAL, CheckKind.BRAND)));
    }
}
