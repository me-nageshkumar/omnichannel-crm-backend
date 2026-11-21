package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/** A template's header file: a campaign manager uploads it for a campaign, an agent for one chat. */
class CloudHeaderUploadPermissionIT extends CloudCampaignTestBase {

    @Autowired RoleRepository roles;
    @Autowired me.nageshk.omnichannel.security.access.RoleCache roleCache;

    private User withOnly(String username, String... authorities) {
        Role r = new Role();
        r.setName(username.toUpperCase() + "_ROLE");
        r.setLevel(RoleLevel.BRAND);
        r.setAuthorities(Set.of(authorities));
        r = roles.save(r);
        roleCache.evictAll();
        return testUsers.createInCustomer(acme.getId(), username, null,
                List.of(new WorkspaceRole(brand.getId(), r.getId())));
    }

    @Test
    void campaignManagerCanUpload() {
        User u = withOnly("campaigner", "campaign:view", "campaign:manage");
        assertThat(uploadPngAs(u).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void inboxSenderStillCanUpload() {
        User u = withOnly("sender", "inbox:view", "inbox:send");
        assertThat(uploadPngAs(u).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void neitherCannotUpload() {
        User u = withOnly("viewer", "campaign:view", "inbox:view");
        var res = uploadPngAs(u);
        assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
