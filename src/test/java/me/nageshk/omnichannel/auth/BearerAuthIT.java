package me.nageshk.omnichannel.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.access.Role;
import me.nageshk.omnichannel.access.RoleCache;
import me.nageshk.omnichannel.access.RoleRepository;
import me.nageshk.omnichannel.config.OmnichannelProperties;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.CustomerRepository;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class BearerAuthIT {

    @Autowired MockMvc mvc;
    @Autowired TestUsers testUsers;
    @Autowired UserRepository repo;
    @Autowired JwtService jwt;
    @Autowired AccessTokenAuthenticator authenticator;
    @Autowired CustomerRepository customers;
    @Autowired RoleRepository roles;
    @Autowired RoleCache roleCache;
    @Autowired OmnichannelProperties props;

    private User user;

    @BeforeEach
    void seed() {
        testUsers.deleteAll();
        user = testUsers.create("dev", "pw", "WHATSAPP_DEVICE", null);
    }

    private ResultActions getStatus(String token) throws Exception {
        return mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token));
    }

    @Test
    void validAccessTokenIsAccepted() throws Exception {
        getStatus(testUsers.accessToken(user)).andExpect(status().isOk());
    }

    @Test
    void pendingTokenRejectedOnProtectedEndpoint() throws Exception {
        getStatus(jwt.issuePendingToken("dev")).andExpect(status().isUnauthorized());
    }

    @Test
    void staleTokenVersionRejected() throws Exception {
        String token = testUsers.accessToken(user);
        user.setTokenVersion(1L);
        repo.save(user);
        getStatus(token).andExpect(status().isUnauthorized());
    }

    @Test
    void deactivatedUserRejectedWithValidToken() throws Exception {
        String token = testUsers.accessToken(user);
        user.setActive(false);
        repo.save(user);
        getStatus(token).andExpect(status().isUnauthorized());
    }

    @Test
    void deletedUserRejected() throws Exception {
        String token = testUsers.accessToken(user);
        repo.delete(user);
        getStatus(token).andExpect(status().isUnauthorized());
    }

    private User customerAdmin(Customer customer) {
        return testUsers.createInCustomer(customer.getId(), "admin", BuiltInRoles.CUSTOMER_ADMIN, List.of());
    }

    private ResultActions bridgeStatus(String token) throws Exception {
        return mvc.perform(get("/api/phone-numbers/any/bridge/status").header("Authorization", "Bearer " + token));
    }

    @Test
    void customerAdminTokenIsForbiddenOnBridgeEndpoints() throws Exception {
        String token = testUsers.accessToken(customerAdmin(testUsers.customer("Acme")));
        bridgeStatus(token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void superAdminMayUseBridge() throws Exception {
        User root = testUsers.create("root", "pw", BuiltInRoles.SUPER_ADMIN, null);
        bridgeStatus(testUsers.accessToken(root)).andExpect(status().isOk());
    }

    @Test
    void tokenRefusedAfterCustomerDeactivated() throws Exception {
        Customer acme = testUsers.customer("Acme");
        String token = testUsers.accessToken(customerAdmin(acme));
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        acme.setActive(false);
        customers.save(acme);

        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void legacyTokenWithRoleClaimStillAccepted() throws Exception {
        Instant now = Instant.now();
        String legacy = Jwts.builder()
                .subject("dev")
                .claim("role", "USER")
                .claim(JwtService.CLAIM_USER_ID, user.getId())
                .claim(JwtService.CLAIM_TOKEN_VERSION, 0L)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(1, ChronoUnit.HOURS)))
                .signWith(Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8)))
                .compact();

        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + legacy)).andExpect(status().isOk());
    }

    @Test
    void authorityRemovedFromRoleAppliesOnNextRequest() {
        String token = testUsers.accessToken(testUsers.create("am", "pw", BuiltInRoles.ACCOUNT_MANAGER, null));
        assertThat(authorityCodes(token)).contains("customer:view");

        Role manager = roles.findByName(BuiltInRoles.ACCOUNT_MANAGER).orElseThrow();
        manager.getAuthorities().remove("customer:view");
        roles.save(manager);
        roleCache.evictAll();

        assertThat(authorityCodes(token)).doesNotContain("customer:view").contains("customer:create");
    }

    private List<String> authorityCodes(String token) {
        return authenticator.authenticate(token).orElseThrow().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }
}
