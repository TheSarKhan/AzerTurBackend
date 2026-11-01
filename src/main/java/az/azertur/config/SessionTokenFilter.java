package az.azertur.config;

import az.azertur.customer.CustomerAuthService;
import az.azertur.staff.StaffAuthService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Resolves an opaque bearer token to either a customer or an office staff session. */
public class SessionTokenFilter extends OncePerRequestFilter {
  private final CustomerAuthService customers;
  private final StaffAuthService staff;

  public SessionTokenFilter(CustomerAuthService customers, StaffAuthService staff) {
    this.customers = customers;
    this.staff = staff;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    String header = req.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      String token = header.substring(7);
      Object principal = null;
      String role = null;
      var customer = customers.authenticate(token);
      if (customer.isPresent()) {
        principal = customer.get();
        role = "ROLE_CUSTOMER";
      } else {
        var member = staff.authenticate(token);
        if (member.isPresent()) {
          principal = member.get();
          role = "ROLE_" + member.get().role();
        }
      }
      if (principal == null) {
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"UNAUTHENTICATED\"}");
        return;
      }
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(
          new UsernamePasswordAuthenticationToken(
              principal, null, List.of(new SimpleGrantedAuthority(role))));
      SecurityContextHolder.setContext(context);
    }
    chain.doFilter(req, res);
  }
}
