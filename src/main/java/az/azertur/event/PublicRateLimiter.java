package az.azertur.event;

import az.azertur.shared.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Per-address request budget for public read endpoints; fails open if Redis is unavailable. */
@Component
public class PublicRateLimiter {
  private final StringRedisTemplate redis;

  public PublicRateLimiter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /** The API sits behind nginx and Cloudflare, so the socket address is the proxy, not the visitor. */
  static String clientAddress(HttpServletRequest req) {
    for (String header : new String[] {"CF-Connecting-IP", "X-Real-IP"}) {
      String v = req.getHeader(header);
      if (v != null && !v.isBlank() && v.length() <= 64) return v.strip();
    }
    return req.getRemoteAddr();
  }

  public void check(HttpServletRequest req, String bucket, int maxPerMinute) {
    try {
      Long n =
          redis.execute(
              new DefaultRedisScript<Long>(
                  "local n=redis.call('INCR',KEYS[1]);if n==1 then redis.call('EXPIRE',KEYS[1],60)"
                      + " end;return n",
                  Long.class),
              List.of("public:" + bucket + ":" + clientAddress(req)));
      if (n != null && n > maxPerMinute)
        throw new DomainException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS");
    } catch (DataAccessException e) {
      // Browsing events should not break because the cache store is down.
    }
  }
}
