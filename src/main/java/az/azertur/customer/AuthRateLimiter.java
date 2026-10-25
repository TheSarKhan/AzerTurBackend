package az.azertur.customer;

import az.azertur.shared.DomainException;
import java.util.List;
import java.util.Locale;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AuthRateLimiter {
  private final StringRedisTemplate redis;
  private final CustomerAuthService auth;

  public AuthRateLimiter(StringRedisTemplate redis, CustomerAuthService auth) {
    this.redis = redis;
    this.auth = auth;
  }

  public void check(String address, String email) {
    // The website BFF shares a backend address; account limits remain independent.
    checkKey("auth-ip:" + address, 300);
    checkKey("auth-account:" + auth.hash(email.trim().toLowerCase(Locale.ROOT)), 10);
  }

  private void checkKey(String key, int maximum) {
    Long n =
        redis.execute(
            new DefaultRedisScript<Long>(
                "local n=redis.call('INCR',KEYS[1]);if n==1 then redis.call('EXPIRE',KEYS[1],900)"
                    + " end;return n",
                Long.class),
            List.of(key));
    if (n != null && n > maximum)
      throw new DomainException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS");
  }
}
