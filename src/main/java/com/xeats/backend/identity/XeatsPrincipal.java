package com.xeats.backend.identity;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class XeatsPrincipal implements UserDetails {
    private final UUID userId;
    private final String username;
    private final String passwordHash;
    private final Set<UserRole> roles;
    private final boolean enabled;

    public XeatsPrincipal(
            UUID userId,
            String username,
            String passwordHash,
            Set<UserRole> roles,
            boolean enabled) {
        this.userId = userId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.roles = Set.copyOf(roles);
        this.enabled = enabled;
    }

    public UUID userId() {
        return userId;
    }

    public Set<UserRole> roles() {
        return roles;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream().map(UserRole::authority).map(SimpleGrantedAuthority::new).toList();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
