package az.azertur.operations;

import az.azertur.catalog.CatalogController;
import az.azertur.catalog.Tour;
import az.azertur.catalog.TourRepository;
import az.azertur.shared.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.text.Normalizer;
import java.util.Locale;
import org.springframework.cache.annotation.CacheEvict;
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
        quotes.findTop20ByOrderByCreatedAtDesc());
  }

  public record NewTour(
      @NotBlank @Size(max = 160) String titleEn,
      @Size(max = 160) String titleRu,
      @Size(max = 160) String titleAz,
      @NotBlank @Size(max = 160) String region,
      @Min(1) @Max(14) int days,
      @Min(1) @Max(100000) int fromPriceEur,
      @NotBlank @Pattern(regexp = "mountains|sheki|gabala|baku") String image) {}

  /** Adds a journey to the catalogue the mobile app and quotes read; the slug comes from the English title. */
  @PostMapping("/tours")
  @CacheEvict(value = CatalogController.TOUR_CACHE, allEntries = true)
  public CatalogController.TourView createTour(@Valid @RequestBody NewTour in) {
    String base =
        Normalizer.normalize(in.titleEn().trim(), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
    if (base.isBlank()) throw DomainException.bad("INVALID_TOUR_TITLE");
    if (base.length() > 70) base = base.substring(0, 70).replaceAll("-$", "");
    String slug = base;
    for (int n = 2; tours.existsById(slug); n++) slug = base + "-" + n;
    String en = in.titleEn().trim();
    var tour =
        new Tour(
            slug,
            en,
            in.titleRu() == null || in.titleRu().isBlank() ? en : in.titleRu().trim(),
            in.titleAz() == null || in.titleAz().isBlank() ? en : in.titleAz().trim(),
            in.region().trim(),
            in.days(),
            in.fromPriceEur(),
            in.image());
    tours.save(tour);
    return CatalogController.view(tour);
  }
}
