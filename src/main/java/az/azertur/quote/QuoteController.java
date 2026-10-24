package az.azertur.quote;
import az.azertur.catalog.TourRepository;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/quotes")
public class QuoteController {
 private final QuoteCalculator calculator;private final TourRepository tours;private final QuoteRepository quotes;
 public QuoteController(QuoteCalculator calculator,TourRepository tours,QuoteRepository quotes){this.calculator=calculator;this.tours=tours;this.quotes=quotes;}
 @PostMapping public ResponseEntity<QuoteResult> create(@Valid @RequestBody QuoteInput input){var tour=tours.findById(input.slug()).orElseThrow(()->new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND,"TOUR_NOT_FOUND"));var q=calculator.calculate(input,tour.days);quotes.save(new QuoteEntity(q));return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(q);}
}
