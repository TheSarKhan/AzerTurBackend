package az.azertur.staff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Creates the very first administrator so a fresh database can be signed in to. */
@Component
public class StaffBootstrap implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(StaffBootstrap.class);
  private final StaffAuthService staff;
  private final String email;
  private final String password;

  public StaffBootstrap(
      StaffAuthService staff,
      @Value("${app.security.bootstrap-admin-email:}") String email,
      @Value("${app.security.bootstrap-admin-password:}") String password) {
    this.staff = staff;
    this.email = email;
    this.password = password;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (email.isBlank() || password.isBlank()) return;
    if (staff.bootstrap(email, "Administrator", password))
      log.info("Created the initial administrator account {}", email);
  }
}
