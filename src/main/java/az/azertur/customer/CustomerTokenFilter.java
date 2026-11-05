package az.azertur.customer;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class CustomerTokenFilter extends OncePerRequestFilter {
  private final CustomerAuthService auth;
  private final az.azertur.staff.StaffAuthService staff;

  public CustomerTokenFilter(CustomerAuthService auth, az.azertur.staff.StaffAuthService staff) {
    this.auth = auth;
    this.staff = staff;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    String header = req.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      var customer = auth.authenticate(header.substring(7));
      if (customer.isEmpty()) {
        var member = staff.authenticate(header.substring(7));
        if (member.isPresent()) {
          var principal = member.get();
          var roles = principal.role().equals("ADMIN")
              ? List.of("ROLE_OPERATIONS", "ROLE_ADMIN", "ROLE_FINANCE")
              : List.of("ROLE_OPERATIONS");
          var context = SecurityContextHolder.createEmptyContext();
          context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
              roles.stream().map(SimpleGrantedAuthority::new).toList()));
          SecurityContextHolder.setContext(context);
          chain.doFilter(req, res);
          return;
        }
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"UNAUTHENTICATED\"}");
        return;
      }
      if(req.getMethod().equals("POST") && req.getRequestURI().startsWith("/api/v1/bookings") && !req.getRequestURI().startsWith("/api/v1/bookings/notifications") && !customer.get().emailVerified()) {
        res.setStatus(403);res.setContentType("application/json");res.getWriter().write("{\"error\":\"EMAIL_VERIFICATION_REQUIRED\"}");return;
      }
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(
          new UsernamePasswordAuthenticationToken(
              customer.get(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
      SecurityContextHolder.setContext(context);
    }
    chain.doFilter(req, res);
  }
}
