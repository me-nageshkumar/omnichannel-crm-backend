package me.nageshk.omnichannel.auth;

import java.util.Locale;
import me.nageshk.omnichannel.user.UserRepository;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class MongoUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public MongoUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public AuthenticatedUser loadUserByUsername(String username) {
        return users.findByUsername(username.toLowerCase(Locale.ROOT))
                .map(AuthenticatedUser::new)
                .orElseThrow(() -> new UsernameNotFoundException("no user '" + username + "'"));
    }
}
