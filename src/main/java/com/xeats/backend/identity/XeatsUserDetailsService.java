package com.xeats.backend.identity;

import java.util.Locale;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class XeatsUserDetailsService implements UserDetailsService {
    private final IdentityRepository identities;

    public XeatsUserDetailsService(IdentityRepository identities) {
        this.identities = identities;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String normalized = username.toLowerCase(Locale.ROOT);
        IdentityRepository.UserAccount account = identities.findByUsername(normalized)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid username or password."));
        return new XeatsPrincipal(account.id(), account.username(), account.passwordHash(), account.roles(),
                account.enabled());
    }
}
