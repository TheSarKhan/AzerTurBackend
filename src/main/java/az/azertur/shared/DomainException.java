package az.azertur.shared;

import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {
  public final HttpStatus status;

  public DomainException(HttpStatus status, String code) {
    super(code);
    this.status = status;
  }

  public static DomainException bad(String code) {
    return new DomainException(HttpStatus.BAD_REQUEST, code);
  }

  public static DomainException conflict(String code) {
    return new DomainException(HttpStatus.CONFLICT, code);
  }

  public static DomainException missing() {
    return new DomainException(HttpStatus.NOT_FOUND, "NOT_FOUND");
  }
}
