package az.azertur.catalog;
import java.util.List;
import java.io.Serializable;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/tours")
public class CatalogController {
 private final TourRepository tours;
 public CatalogController(TourRepository tours){this.tours=tours;}
 public record TourView(String slug,String titleEn,String titleRu,String region,int days,int fromPriceEur,String image) implements Serializable{}
 @GetMapping @Cacheable("tours") public List<TourView> list(){return tours.findAll().stream().map(t->new TourView(t.slug,t.titleEn,t.titleRu,t.region,t.days,t.fromPriceEur,t.image)).toList();}
}
