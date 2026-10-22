package com.portfolio.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import java.util.UUID;

@Configuration
public class GallerySecurityConfig {
    @Bean
    UserDetailsService galleryUsers(@Value("${GALLERY_ADMIN_PASSWORD:}") String password) {
        boolean enabled = !password.isBlank();
        if (enabled && (password.length() < 10 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)) {
            throw new IllegalArgumentException("GALLERY_ADMIN_PASSWORD must be at least 10 characters and at most 72 UTF-8 bytes");
        }
        return new InMemoryUserDetailsManager(User.withUsername("admin")
            .password("{bcrypt}" + new BCryptPasswordEncoder().encode(enabled ? password : UUID.randomUUID().toString()))
            .roles("GALLERY_ADMIN").disabled(!enabled).build());
    }

    @Bean @Order(1)
    SecurityFilterChain galleryAdmin(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/admin/**")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/admin/session", "/api/admin/login").permitAll()
                .anyRequest().hasRole("GALLERY_ADMIN"))
            .formLogin(form -> form.loginProcessingUrl("/api/admin/login")
                .successHandler((req, res, auth) -> res.setStatus(204))
                .failureHandler((req, res, ex) -> res.setStatus(401)))
            .logout(logout -> logout.logoutUrl("/api/admin/logout")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
            .exceptionHandling(errors -> errors.authenticationEntryPoint((req, res, ex) -> res.setStatus(401)))
            .requestCache(cache -> cache.disable())
            // Default session-backed CSRF protection covers login, upload and logout.
            .build();
    }

    @Bean @Order(2)
    SecurityFilterChain publicPortfolio(HttpSecurity http) throws Exception {
        // Preserve the existing public chatbot and document APIs.
        return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable()).logout(logout -> logout.disable()).build();
    }
}
