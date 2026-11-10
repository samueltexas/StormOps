package com.stormops.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** Local-only role-based authentication. Enable only after frontend login is integrated. */
@Configuration
public class StormOpsSecurityConfig {
    @Bean
    PasswordEncoder stormOpsPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService stormOpsUsers(
            @Value("${stormops.security.admin.username:admin}") String adminUsername,
            @Value("${stormops.security.admin.password:}") String adminPassword,
            @Value("${stormops.security.viewer.username:viewer}") String viewerUsername,
            @Value("${stormops.security.viewer.password:}") String viewerPassword,
            @Value("${stormops.security.enabled:false}") boolean enabled,
            PasswordEncoder encoder) {
        if (!enabled) return new InMemoryUserDetailsManager();
        if (adminPassword.isBlank() || viewerPassword.isBlank()) {
            throw new IllegalStateException("Security enabled but admin/viewer passwords are missing");
        }
        if (adminUsername.isBlank() || viewerUsername.isBlank() || adminUsername.equals(viewerUsername)) {
            throw new IllegalStateException("Admin and viewer usernames must be nonempty and distinct");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(adminUsername).password(encoder.encode(adminPassword)).roles("ADMIN").build(),
                User.withUsername(viewerUsername).password(encoder.encode(viewerPassword)).roles("VIEWER").build());
    }

    @Bean
    SecurityFilterChain stormOpsSecurityFilterChain(
            HttpSecurity http,
            @Value("${stormops.security.enabled:false}") boolean enabled) throws Exception {
        // This API uses stateless HTTP Basic, not cookie authentication. Browser login follows in Phase 10C.
        http.csrf(csrf -> csrf.disable());
        http.headers(headers -> headers.cacheControl(Customizer.withDefaults()));
        if (!enabled) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            http.httpBasic(basic -> basic.disable());
        } else {
            http.authorizeHttpRequests(auth -> auth
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/experiments/pod-termination", "/api/v1/experiments/http-simulation").hasRole("ADMIN")
                    .requestMatchers("/api/v1/**").hasAnyRole("ADMIN", "VIEWER")
                    .anyRequest().denyAll());
            http.httpBasic(Customizer.withDefaults());
        }
        return http.build();
    }
}
