package com.xeats.backend.common.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

import com.xeats.backend.identity.RoleAuthorizationManager;
import com.xeats.backend.identity.UserRole;
import com.xeats.backend.identity.XeatsSecurityProperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(XeatsSecurityProperties.class)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain applicationSecurity(
            HttpSecurity http,
            CsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository,
            RoleAuthorizationManager roleAuthorizationManager) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/orders/**", "/api/v1/me/**", "/api/v1/counters/*/order-options")
                        .access(roleAuthorizationManager.requireRole(UserRole.EMPLOYEE))
                        .requestMatchers("/api/v1/staff/**")
                        .access(roleAuthorizationManager.requireAnyRole(UserRole.VENDOR_STAFF, UserRole.DEMO_ADMIN))
                        .requestMatchers("/api/v1/admin/**")
                        .access(roleAuthorizationManager.requireRole(UserRole.DEMO_ADMIN))
                        .anyRequest().access(roleAuthorizationManager.requireActiveUser()))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeProblem(response,
                                401, "UNAUTHENTICATED", "Unauthorized", "Authentication is required."))
                        .accessDeniedHandler((request, response, exception) -> writeProblem(response,
                                403, "FORBIDDEN", "Forbidden", "This action is not permitted.")))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository))
                .logout(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-XSRF-TOKEN");
        repository.setParameterName("_csrf");
        return repository;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        HttpSessionSecurityContextRepository repository = new HttpSessionSecurityContextRepository();
        repository.setDisableUrlRewriting(true);
        return repository;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityContextLogoutHandler securityContextLogoutHandler(SecurityContextRepository repository) {
        SecurityContextLogoutHandler handler = new SecurityContextLogoutHandler();
        handler.setSecurityContextRepository(repository);
        return handler;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(XeatsSecurityProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of(
                HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.POST.name(), HttpMethod.PUT.name(),
                HttpMethod.PATCH.name(), HttpMethod.DELETE.name(), HttpMethod.OPTIONS.name()));
        configuration.setAllowedHeaders(List.of(
                "Accept", "Content-Type", "If-Match", "Idempotency-Key", "X-XSRF-TOKEN"));
        configuration.setExposedHeaders(List.of("ETag", "Idempotency-Replayed", "Location"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", configuration);
        return source;
    }

    private void writeProblem(HttpServletResponse response, int status, String code, String title, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"urn:xeats:problem:" + code.toLowerCase().replace('_', '-')
                + "\",\"title\":\"" + title + "\",\"status\":" + status + ",\"code\":\"" + code
                + "\",\"detail\":\"" + detail + "\"}");
    }
}
