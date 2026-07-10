package com.vaultflow.upload.config;

import com.vaultflow.common.tracing.CorrelationIdFilter;
import jakarta.servlet.Filter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

  private final OncePerRequestFilter jwtAuthFilter;
  private final CorrelationIdFilter correlationIdFilter;

  public SecurityConfig(
      @Qualifier("jwtAuthFilter") OncePerRequestFilter jwtAuthFilter,
      CorrelationIdFilter correlationIdFilter) {
    this.jwtAuthFilter = jwtAuthFilter;
    this.correlationIdFilter = correlationIdFilter;
  }

    @Qualifier("jwtAuthFilter")
    @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health", "/actuator/info").permitAll()
            .requestMatchers("/actuator/**").authenticated()
            .requestMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()
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
}
