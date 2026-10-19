package me.nageshk.omnichannel.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootTest
class UserRepositoryIT {

    @Autowired UserRepository repo;
    @Autowired MongoTemplate template;

    @BeforeEach
    void clean() {
        repo.deleteAll();
    }

    private static User newUser(String username) {
        User u = new User();
        u.setName("Test " + username);
        u.setUsername(username);
        u.setPassword("x");
        u.setRoleId("r-user");
        u.setActive(true);
        u.setTwoFactor(TwoFactor.disabled());
        return u;
    }

    @Test
    void saveSetsAuditFieldsAndClass() {
        User u = newUser("alice");
        repo.save(u);

        Document raw = template.getCollection("users").find().first();
        assertThat(u.getCreationTime()).isNotNull();
        assertThat(u.getLastUpdateTime()).isNotNull();
        assertThat(raw.getString("_class")).isEqualTo("me.nageshk.omnichannel.user.User");
        assertThat(raw.get("tokenVersion")).isInstanceOf(Long.class);
        assertThat(raw.getString("roleId")).isEqualTo("r-user");
    }

    @Test
    void usernameIsUnique() {
        repo.save(newUser("bob"));
        assertThatThrownBy(() -> repo.save(newUser("bob"))).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void findByUsernameAndExistsByRoleId() {
        repo.save(newUser("carol"));
        assertThat(repo.findByUsername("carol")).isPresent();
        assertThat(repo.findByUsername("CAROL")).isEmpty();
        assertThat(repo.existsByRoleId("r-user")).isTrue();
        assertThat(repo.existsByRoleId("other")).isFalse();
    }

    @Test
    void staleCopyCannotUndoDeactivation() {
        User saved = repo.save(newUser("dave"));
        User stale = repo.findById(saved.getId()).orElseThrow();

        User fresh = repo.findById(saved.getId()).orElseThrow();
        fresh.setActive(false);
        fresh.setTokenVersion(fresh.getTokenVersion() + 1);
        repo.save(fresh);

        stale.setName("Renamed");
        assertThatThrownBy(() -> repo.save(stale)).isInstanceOf(OptimisticLockingFailureException.class);
        User now = repo.findById(saved.getId()).orElseThrow();
        assertThat(now.isActive()).isFalse();
        assertThat(now.getTokenVersion()).isEqualTo(1L);
    }
}
