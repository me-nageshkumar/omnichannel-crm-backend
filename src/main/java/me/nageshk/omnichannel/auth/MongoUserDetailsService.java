package me.nageshk.omnichannel.auth;

import java.util.Locale;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.user.UserRepository;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class MongoUserDetailsService implements UserDetailsService {

    private final UserRepository users;
    private final AccessService access;

    public MongoUserDetailsService(UserRepository users, AccessService access) {
        this.users = users;
        this.access = access;
    }

    @Override
    public AuthenticatedUser loadUserByUsername(String username) {
        return users.findByUsername(username.toLowerCase(Locale.ROOT))
                .map(access::load)
                .orElseThrow(() -> new UsernameNotFoundException("no user '" + username + "'"));
    }
}
