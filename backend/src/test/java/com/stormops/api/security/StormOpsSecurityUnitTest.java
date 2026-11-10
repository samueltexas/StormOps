package com.stormops.api.security;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

class StormOpsSecurityUnitTest {
    @Test void configuredUsersHaveDistinctRolesAndEncodedPasswords() {
        var config = new StormOpsSecurityConfig();
        var encoder = new BCryptPasswordEncoder();
        var users = config.stormOpsUsers("admin", "local-admin-secret", "viewer", "local-viewer-secret", true, encoder);
        var admin = users.loadUserByUsername("admin");
        var viewer = users.loadUserByUsername("viewer");
        assertTrue(encoder.matches("local-admin-secret", admin.getPassword()));
        assertNotEquals("local-admin-secret", admin.getPassword());
        assertTrue(admin.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertTrue(viewer.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_VIEWER")));
        assertFalse(viewer.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test void enabledSecurityRejectsMissingOrDuplicateCredentials() {
        var config = new StormOpsSecurityConfig();
        var encoder = new BCryptPasswordEncoder();
        assertThrows(IllegalStateException.class, () -> config.stormOpsUsers("admin", "", "viewer", "secret", true, encoder));
        assertThrows(IllegalStateException.class, () -> config.stormOpsUsers("same", "a", "same", "b", true, encoder));
    }

    @Test void authEndpointReportsRoleFromServerAuthentication() {
        var controller = new AuthController();
        var viewer = new UsernamePasswordAuthenticationToken("viewer", "unused", java.util.List.of(new SimpleGrantedAuthority("ROLE_VIEWER")));
        assertEquals(Map.of("username", "viewer", "role", "VIEWER"), controller.me(viewer));
        var ex = assertThrows(ResponseStatusException.class, () -> controller.me(null));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }
}
