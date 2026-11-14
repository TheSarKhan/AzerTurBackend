package az.azertur.quote;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import jakarta.validation.Valid;
import java.util.LinkedHashSet;
import java.util.List;

public record QuoteInput(
    @NotBlank @Size(max = 80) String slug,
    @NotNull LocalDate date,
    // 1-4 exact; larger groups arrive as the top of their band (10, 20, 50, 100) or 101 for 100+.
    @Min(1) @Max(101) int pax,
    @NotNull @Pattern(regexp = "standard|comfort|premium") String hotel,
    Boolean driver,
    Boolean stay,
    boolean guide,
    @Pattern(regexp = "en|ru|az") String guideLanguage,
    boolean transfer,
    boolean activity,
    @NotNull @Pattern(regexp = "EUR|USD|AZN") String currency,
    @Min(1) @Max(14) Integer customDays,
    @Size(min = 1, max = 14) List<@NotBlank String> cities,
    // Extras the customer wants in the offer; they are not priced here, the office adds them to the offer.
    @Size(max = 4) List<@Pattern(regexp = "event|meals|tickets|photographer") String> extras,
    // Where the trip starts and where it ends. They take no days and no price; they default to Baku.
    @Pattern(regexp = "Baku|Quba|Khinalug|Gabala|Sheki") String startCity,
    @Pattern(regexp = "Baku|Quba|Khinalug|Gabala|Sheki") String endCity,
    // The trip day by day: where the customer goes each day and which services they want in each place.
    // When it is given, the fields above (days, cities, the service flags, extras) are worked out from it.
    @Size(min = 1, max = 14) List<@Valid @NotNull DayPlan> plan) {

  public record Place(
      @NotNull @Pattern(regexp = "Baku|Quba|Khinalug|Gabala|Sheki") String city,
      @NotNull
          @Size(max = 9)
          List<
                  @Pattern(
                      regexp =
                          "transport|hotel|guide|transfer|intercity|activity|event|meals|tickets|photographer")
                  String>
              services) {}

  public record DayPlan(@NotNull @Size(min = 1, max = 4) List<@Valid @NotNull Place> places) {}

  private boolean any(String service) {
    return plan.stream().anyMatch(d -> d.places().stream().anyMatch(p -> p.services().contains(service)));
  }

  /**
   * The same request with the start and end filled in, so a stored quote always carries them. A day plan
   * also fixes the number of days, the route (the first place of each day) and the service flags.
   */
  public QuoteInput withEnds() {
    QuoteInput q = this;
    if (plan != null) {
      var extra = new LinkedHashSet<String>();
      for (String e : List.of("event", "meals", "tickets", "photographer")) if (any(e)) extra.add(e);
      q =
          new QuoteInput(
              slug, date, pax, hotel, any("transport"), any("hotel"), any("guide"), guideLanguage,
              any("transfer") || any("intercity"), any("activity"), currency, plan.size(),
              plan.stream().map(d -> d.places().get(0).city()).toList(), List.copyOf(extra),
              startCity, endCity, plan);
    }
    return new QuoteInput(
        q.slug, q.date, q.pax, q.hotel, q.driver, q.stay, q.guide, q.guideLanguage, q.transfer,
        q.activity, q.currency, q.customDays, q.cities, q.extras,
        q.startCity == null ? "Baku" : q.startCity, q.endCity == null ? "Baku" : q.endCity, q.plan);
  }
}
