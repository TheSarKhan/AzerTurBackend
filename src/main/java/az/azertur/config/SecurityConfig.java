package az.azertur.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.*;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      az.azertur.customer.CustomerAuthService customers,
      az.azertur.staff.StaffAuthService staff)
      throws Exception {
    return http.cors(c -> {})
        .csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(
            new SessionTokenFilter(customers, staff),
            org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                .class)
        .authorizeHttpRequests(
            a ->
                a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/staff/auth/login")
                    .permitAll()
                    .requestMatchers("/api/v1/staff/auth/me", "/api/v1/staff/auth/logout")
                    .hasAnyRole("OPERATOR", "ADMIN")
                    .requestMatchers(
                        "/api/v1/auth/me", "/api/v1/auth/logout", "/api/v1/bookings/**")
                    .hasRole("CUSTOMER")
                    .requestMatchers(
                        "/actuator/health",
                        "/api/v1/tours/**",
                        "/api/v1/quotes",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers("/api/v1/operations/**")
                    .hasAnyRole("OPERATOR", "ADMIN")
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(@Value("${app.cors-origins}") String origins) {
    var c = new CorsConfiguration();
    c.setAllowedOrigins(Arrays.asList(origins.split(",")));
    c.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
    c.setAllowedHeaders(List.of("Content-Type", "Authorization", "Idempotency-Key"));
    c.setAllowCredentials(false);
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", c);
    return source;
  }
}
