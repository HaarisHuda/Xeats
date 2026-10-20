package com.xeats.backend.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import com.xeats.backend.common.web.ApiErrorCode;
import com.xeats.backend.common.web.ApiProblemException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final IdentityRepository identities;
    private final LoginAttemptService loginAttempts;
    private final SecurityContextLogoutHandler logoutHandler;

    public AuthController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            IdentityRepository identities,
            LoginAttemptService loginAttempts,
            SecurityContextLogoutHandler logoutHandler) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.identities = identities;
        this.loginAttempts = loginAttempts;
        this.logoutHandler = logoutHandler;
    }

    @GetMapping("/csrf")
    public CsrfTokenResponse csrf(CsrfToken csrfToken) {
        return new CsrfTokenResponse(csrfToken.getHeaderName(), csrfToken.getToken());
    }

    @PostMapping("/login")
    public CurrentUserResponse login(
            @Valid @RequestBody LoginRequest loginRequest,
            HttpServletRequest request,
            HttpServletResponse response) {
        String username = loginRequest.username().toLowerCase(java.util.Locale.ROOT);
        String remoteAddress = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        if (!loginAttempts.tryStart(username, remoteAddress)) {
            throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCode.RATE_LIMITED,
                    "Too many sign-in attempts. Try again later.");
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, loginRequest.password()));
        } catch (BadCredentialsException | DisabledException exception) {
            loginAttempts.failed(username, remoteAddress);
            throw new ApiProblemException(HttpStatus.UNAUTHORIZED, ApiErrorCode.UNAUTHENTICATED,
                    "Invalid username or password.");
        }

        loginAttempts.succeeded(username, remoteAddress);
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        new CsrfAuthenticationStrategy(csrfTokenRepository).onAuthentication(authentication, request, response);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        XeatsPrincipal principal = (XeatsPrincipal) authentication.getPrincipal();
        return identities.getCurrentUser(principal.userId());
    }

    @GetMapping("/me")
    public CurrentUserResponse currentUser(Authentication authentication) {
        XeatsPrincipal principal = (XeatsPrincipal) authentication.getPrincipal();
        return identities.getCurrentUser(principal.userId());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        csrfTokenRepository.saveToken(null, request, response);
        logoutHandler.logout(request, response, authentication);
        return ResponseEntity.noContent().build();
    }
}
