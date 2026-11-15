package az.azertur.catalog;

import java.io.Serializable;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tours")
public class CatalogController {
  public static final String TOUR_CACHE = "tours-v3";
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
      String image,
      Double latitude,
      Double longitude)
      implements Serializable {}

  public static TourView view(Tour tour) {
    return new TourView(
        tour.slug, tour.titleEn, tour.titleRu, tour.titleAz,
        tour.region, tour.days, tour.fromPriceEur, tour.image,
        tour.latitude, tour.longitude);
  }

  @GetMapping
  @Cacheable(TOUR_CACHE)
  public List<TourView> list() {
    return tours.findAll().stream().map(CatalogController::view).toList();
  }
}
