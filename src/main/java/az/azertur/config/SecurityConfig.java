package az.azertur.config;
import java.util.List;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;
@Configuration
public class SecurityConfig {
 @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
  return http.cors(c->{}).csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/api/v1/tours/**","/api/v1/quotes","/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html").permitAll().requestMatchers("/api/v1/operations/**").hasRole("OPERATIONS").anyRequest().denyAll())
   .httpBasic(b->{}).build();
 }
 @Bean UserDetailsService users(@Value("${app.security.operations-user}") String user,@Value("${app.security.operations-password}") String password){
  if(password.isBlank()||password.length()<16)throw new IllegalStateException("OPERATIONS_PASSWORD must have at least 16 characters");
  return new InMemoryUserDetailsManager(User.withUsername(user).password("{bcrypt}"+new BCryptPasswordEncoder().encode(password)).roles("OPERATIONS").build());
 }
 @Bean CorsConfigurationSource corsConfigurationSource(@Value("${app.cors-origins}") String origins){
  var c=new CorsConfiguration();c.setAllowedOrigins(Arrays.asList(origins.split(",")));c.setAllowedMethods(List.of("GET","POST","OPTIONS"));c.setAllowedHeaders(List.of("Content-Type","Authorization"));c.setAllowCredentials(false);
  var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",c);return source;
 }
}
