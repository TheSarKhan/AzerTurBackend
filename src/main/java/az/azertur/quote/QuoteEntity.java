package az.azertur.quote;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "quotes")
public class QuoteEntity {
  @Id public UUID id;
  public String tourSlug;
  public String currency;
  public int total;
  public int deposit;
  public LocalDate departure;
  public Instant expiresAt;
  public Instant createdAt;

  @Column(columnDefinition = "text")
  public String payload;

  protected QuoteEntity() {}

  public QuoteEntity(QuoteResult q) {
    id = q.id();
    tourSlug = q.input().slug();
    currency = q.currency();
    total = q.total();
    deposit = q.deposit();
    departure = q.input().date();
    expiresAt = q.expires();
    createdAt = Instant.now();
  }
}
