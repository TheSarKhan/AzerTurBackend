package az.azertur.catalog;

import java.io.Serializable;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tours")
public class CatalogController {
  private final TourRepository tours;

  public CatalogController(TourRepository tours) {
    this.tours = tours;
  }

  public record TourView(
      String slug,
      String titleEn,
      String titleRu,
      String titleAz,
      String region,
      int days,
      int fromPriceEur,
      String image)
      implements Serializable {}

  @GetMapping
  @Cacheable("tours-v2")
  public List<TourView> list() {
    return tours.findAll().stream()
        .map(
            t ->
                new TourView(
                    t.slug,
                    t.titleEn,
                    t.titleRu,
                    t.titleAz,
                    t.region,
                    t.days,
                    t.fromPriceEur,
                    t.image))
        .toList();
  }
}
