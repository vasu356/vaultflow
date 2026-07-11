package com.vaultflow.admin.config;

import com.vaultflow.common.tracing.CorrelationIdFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

  private static final String UNAUTHORIZED_BODY =
      "{\"errorCode\":\"UNAUTHORIZED\",\"message\":\"Authentication required\",\"status\":401}";
  private static final String FORBIDDEN_BODY =
      "{\"errorCode\":\"FORBIDDEN\",\"message\":\"Admin access required\",\"status\":403}";

  private final OncePerRequestFilter jwtAuthFilter;
  private final CorrelationIdFilter correlationIdFilter;

  public SecurityConfig(
      @Qualifier("jwtAuthFilter") OncePerRequestFilter jwtAuthFilter,
      CorrelationIdFilter correlationIdFilter) {
    this.jwtAuthFilter = jwtAuthFilter;
    this.correlationIdFilter = correlationIdFilter;
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/actuator/info")
                    .permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(
                        (req, res, e) -> {
                          res.setStatus(401);
                          res.setContentType("application/json");
                          res.getWriter().write(UNAUTHORIZED_BODY);
                        })
                    .accessDeniedHandler(
                        (req, res, e) -> {
                          res.setStatus(403);
                          res.setContentType("application/json");
                          res.getWriter().write(FORBIDDEN_BODY);
                        }))
        .build();
  }
}
