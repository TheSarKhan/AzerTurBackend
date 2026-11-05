package az.azertur.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain security(HttpSecurity http, az.azertur.customer.CustomerAuthService auth, az.azertur.staff.StaffAuthService staff)
      throws Exception {
    return http.cors(c -> {})
        .csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(
            new az.azertur.customer.CustomerTokenFilter(auth, staff),
            org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                .class)
        .authorizeHttpRequests(
            a ->
                a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers("/api/v1/staff/auth/login", "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password", "/api/v1/auth/verify-email", "/api/v1/auth/google", "/api/v1/auth/google/config")
                    .permitAll()
                    .requestMatchers(
                        "/api/v1/auth/me", "/api/v1/auth/logout", "/api/v1/auth/logout-all", "/api/v1/auth/email-verification/**", "/api/v1/auth/sessions/**", "/api/v1/bookings/**")
                    .hasRole("CUSTOMER")
                    .requestMatchers(
                        "/actuator/health",
                        "/api/v1/tours/**",
                        "/api/v1/quotes",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers("/api/v1/partner-sync/**").permitAll()
                    .requestMatchers("/api/v1/staff/auth/me", "/api/v1/staff/auth/logout").hasRole("OPERATIONS")
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/v1/operations/staff/directory").hasRole("OPERATIONS")
                    .requestMatchers("/api/v1/operations/partners/*/sync-key").hasRole("ADMIN")
                    .requestMatchers("/api/v1/operations/staff", "/api/v1/operations/office-audit").hasRole("ADMIN")
                    .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/operations/bookings/*/allocation","/api/v1/operations/bookings/*/revised-plan","/api/v1/operations/refunds/*","/api/v1/operations/requests/*/amendment/send").hasRole("FINANCE")
                    .requestMatchers("/api/v1/operations/**")
                    .hasRole("OPERATIONS")
                    .anyRequest()
                    .denyAll())
        .httpBasic(b -> {})
        .build();
  }

  @Bean
  UserDetailsService users(
      @Value("${app.security.operations-user}") String user,
      @Value("${app.security.operations-password}") String password, org.springframework.jdbc.core.JdbcTemplate db) {
    if (password.isBlank() || password.length() < 16)
      throw new IllegalStateException("OPERATIONS_PASSWORD must have at least 16 characters");
    String encoded="{bcrypt}"+new BCryptPasswordEncoder().encode(password);
    return username -> {
      if(username.equals(user)) return User.withUsername(user).password(encoded).roles("OPERATIONS","ADMIN","FINANCE").build();
      var rows=db.queryForList("SELECT * FROM staff_accounts WHERE username=? AND enabled=true",username);
      if(rows.isEmpty()) throw new UsernameNotFoundException("Unknown account");
      var r=rows.getFirst();String role=(String)r.get("role");
      String[] roles=role.equals("ADMIN")?new String[]{"OPERATIONS","ADMIN","FINANCE"}:role.equals("FINANCE")?new String[]{"OPERATIONS","FINANCE"}:new String[]{"OPERATIONS"};
      return User.withUsername(username).password((String)r.get("password_hash")).roles(roles).build();
    };
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
