package me.nageshk.omnichannel.security.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import me.nageshk.omnichannel.db.access.AuthorityDefinition;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.security.access.AuthorityCheckScanner.CheckKind;
import me.nageshk.omnichannel.security.access.AuthorityCheckScanner.FoundCheck;
import org.junit.jupiter.api.Test;

class AuthorityServiceTest {

    private static AuthorityDefinition def(RoleLevel level, boolean builtIn) {
        AuthorityDefinition d = new AuthorityDefinition();
        d.setCode("a:b");
        d.setGroup("a");
        d.setLevel(level);
        d.setBuiltIn(builtIn);
        return d;
    }

    private static FoundCheck found(CheckKind first, CheckKind... rest) {
        return new FoundCheck("a:b", EnumSet.of(first, rest), List.of("X.y"));
    }

    @Test
    void builtInIsActiveEvenWhenNotChecked() {
        assertThat(AuthorityStatus.statusOf(def(RoleLevel.COMPANY, true), null)).isEqualTo(AuthorityStatus.ACTIVE);
    }

    @Test
    void builtInIsActiveEvenAtWrongLevel() {
        assertThat(AuthorityStatus.statusOf(def(RoleLevel.BRAND, true), found(CheckKind.GLOBAL)))
                .isEqualTo(AuthorityStatus.ACTIVE);
    }

    @Test
    void uncheckedIsNotChecked() {
        assertThat(AuthorityStatus.statusOf(def(RoleLevel.BRAND, false), null)).isEqualTo(AuthorityStatus.NOT_CHECKED);
    }

    @Test
    void brandCheckedOnlyGloballyIsWrongLevel() {
        assertThat(AuthorityStatus.statusOf(def(RoleLevel.BRAND, false), found(CheckKind.GLOBAL)))
                .isEqualTo(AuthorityStatus.WRONG_LEVEL);
    }

    @Test
    void platformCheckedOnlyForBrandIsWrongLevel() {
        assertThat(AuthorityStatus.statusOf(def(RoleLevel.PLATFORM, false), found(CheckKind.BRAND)))
                .isEqualTo(AuthorityStatus.WRONG_LEVEL);
    }

    @Test
    void companyCheckedOnlyForBrandIsActive() {
        assertThat(AuthorityStatus.statusOf(def(RoleLevel.COMPANY, false), found(CheckKind.BRAND)))
                .isEqualTo(AuthorityStatus.ACTIVE);
    }

    @Test
    void companyCheckedOnlyGloballyIsActive() {
        assertThat(AuthorityStatus.statusOf(def(RoleLevel.COMPANY, false), found(CheckKind.GLOBAL)))
                .isEqualTo(AuthorityStatus.ACTIVE);
    }

    @Test
    void fittingChecksAreActive() {
        assertThat(AuthorityStatus.statusOf(def(RoleLevel.BRAND, false), found(CheckKind.BRAND)))
                .isEqualTo(AuthorityStatus.ACTIVE);
        assertThat(AuthorityStatus.statusOf(def(RoleLevel.PLATFORM, false), found(CheckKind.GLOBAL)))
                .isEqualTo(AuthorityStatus.ACTIVE);
        assertThat(AuthorityStatus.statusOf(def(RoleLevel.BRAND, false), found(CheckKind.GLOBAL, CheckKind.BRAND)))
                .isEqualTo(AuthorityStatus.ACTIVE);
    }
}
