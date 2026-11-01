package az.azertur.operations;

import az.azertur.catalog.TourRepository;
import az.azertur.quote.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "operationsAuth")
@RestController
@RequestMapping("/api/v1/operations")
public class OperationsController {
  private final TourRepository tours;
  private final QuoteRepository quotes;

  public OperationsController(TourRepository tours, QuoteRepository quotes) {
    this.tours = tours;
    this.quotes = quotes;
  }

  @GetMapping("/overview")
  public Map<String, Object> overview() {
    return Map.of(
        "tourCount",
        tours.count(),
        "quoteCount",
        quotes.count(),
        "recentQuotes",
        quotes.findTop20ByOrderByCreatedAtDesc(),
        "mode",
        "DEMO_PRICING");
  }
}
