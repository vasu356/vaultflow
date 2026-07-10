package com.vaultflow.auth.config;

import com.vaultflow.auth.filter.JwtAuthenticationFilter;
import com.vaultflow.common.tracing.CorrelationIdFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthFilter;
  private final CorrelationIdFilter correlationIdFilter;

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(AbstractHttpConfigurer::disable) // Stateless JWT — CSRF not applicable
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            // Public endpoints
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
            .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
            // Actuator — restrict in production via NGINX/network policy
            .requestMatchers("/actuator/health", "/actuator/info").permitAll()
            .requestMatchers("/actuator/**").hasRole("ADMIN")
            // OpenAPI
            .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
            // Everything else requires authentication
            .anyRequest().authenticated())
        .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(ex -> ex
            .authenticationEntryPoint((req, res, e) -> {
              res.setStatus(401);
              res.setContentType("application/json");
              res.getWriter().write(
                  "{\"errorCode\":\"UNAUTHORIZED\",\"message\":\"Authentication required\",\"status\":401}");
            })
            .accessDeniedHandler((req, res, e) -> {
              res.setStatus(403);
              res.setContentType("application/json");
              res.getWriter().write(
                  "{\"errorCode\":\"FORBIDDEN\",\"message\":\"Insufficient permissions\",\"status\":403}");
            }))
        .build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    // BCrypt cost factor 12: ~250ms per hash — acceptable for login, strong against brute force.
    // Cost 10 would be faster but more vulnerable. Cost 14 too slow for high-concurrency login.
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    // In production: load allowed origins from environment config, never use wildcard
    config.setAllowedOriginPatterns(List.of("https://*.vaultflow.io", "http://localhost:*"));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of(
        "Authorization", "Content-Type", "X-Correlation-ID",
        "X-Requested-With", "Accept", "Origin"));
    config.setExposedHeaders(List.of("X-Correlation-ID", "X-RateLimit-Remaining"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L); // Cache preflight for 1 hour

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
