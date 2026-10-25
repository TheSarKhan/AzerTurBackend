package az.azertur.quote;

import java.time.*;
import java.util.*;

public record QuoteResult(
    UUID id,
    String status,
    String currency,
    int total,
    int perPerson,
    int deposit,
    int balance,
    LocalDate due,
    Instant expires,
    List<Line> lines,
    QuoteInput input,
    boolean demo) {
  public record Line(String label, int amount) {}
}
