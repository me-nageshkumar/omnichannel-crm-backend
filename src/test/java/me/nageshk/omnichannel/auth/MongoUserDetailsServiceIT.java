package me.nageshk.omnichannel.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRepository;
import me.nageshk.omnichannel.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;
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
    void loadsActiveUserWithRoleAuthority() {
        testUsers.create("alice", "pw", UserRole.USER, null);

        UserDetails details = service.loadUserByUsername("alice");

        assertThat(details).isInstanceOf(AuthenticatedUser.class);
        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
        assertThat(details.isEnabled()).isTrue();
        assertThat(((AuthenticatedUser) details).tokenVersion()).isZero();
        assertThat(((AuthenticatedUser) details).twoFactorEnabled()).isFalse();
    }

    @Test
    void inactiveUserIsDisabled() {
        User u = testUsers.create("bob", "pw", UserRole.USER, null);
        u.setActive(false);
        repo.save(u);

        assertThat(service.loadUserByUsername("bob").isEnabled()).isFalse();
    }

    @Test
    void unknownUsernameThrows() {
        assertThatThrownBy(() -> service.loadUserByUsername("nobody")).isInstanceOf(UsernameNotFoundException.class);
    }
}
