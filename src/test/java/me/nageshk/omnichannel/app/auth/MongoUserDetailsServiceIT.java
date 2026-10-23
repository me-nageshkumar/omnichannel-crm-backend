package me.nageshk.omnichannel.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.security.auth.MongoUserDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@SpringBootTest
class MongoUserDetailsServiceIT {

    @Autowired MongoUserDetailsService service;
    @Autowired TestUsers testUsers;
    @Autowired UserRepository repo;

    @BeforeEach
    void clean() {
        testUsers.deleteAll();
    }

    @Test
    void loadsActiveUserWithRoleId() {
        testUsers.create("alice", "pw", "ACCOUNT_MANAGER", null);

        UserDetails details = service.loadUserByUsername("alice");

        assertThat(details).isInstanceOf(AuthenticatedUser.class);
        assertThat(((AuthenticatedUser) details).roleId()).isEqualTo(testUsers.roleId("ACCOUNT_MANAGER"));
        assertThat(details.isEnabled()).isTrue();
        assertThat(((AuthenticatedUser) details).tokenVersion()).isZero();
        assertThat(((AuthenticatedUser) details).twoFactorEnabled()).isFalse();
    }

    @Test
    void inactiveUserIsDisabled() {
        User u = testUsers.create("bob", "pw", "ACCOUNT_MANAGER", null);
        u.setActive(false);
        repo.save(u);

        assertThat(service.loadUserByUsername("bob").isEnabled()).isFalse();
    }

    @Test
    void unknownUsernameThrows() {
        assertThatThrownBy(() -> service.loadUserByUsername("nobody")).isInstanceOf(UsernameNotFoundException.class);
    }
}
