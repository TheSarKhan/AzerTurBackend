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

  public CustomerTokenFilter(CustomerAuthService auth) {
    this.auth = auth;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    String header = req.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      var customer = auth.authenticate(header.substring(7));
      if (customer.isEmpty()) {
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"UNAUTHENTICATED\"}");
        return;
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
