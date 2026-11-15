package az.azertur.catalog;

import java.util.Arrays;
import java.util.List;

/** The categories a journey can belong to. Same list as the website's category filter. */
public final class TourCategories {
  public static final List<String> ALL =
      List.of("history", "culture", "medical", "nature", "entertainment", "business");

  private TourCategories() {}

  /** What a new journey gets when none are given: the same rule the website has always used. */
  public static String forImage(String image) {
    return switch (image == null ? "" : image) {
      case "baku" -> "history,culture,entertainment";
      case "sheki" -> "history,culture,nature";
      case "gabala", "mountains" -> "nature";
      default -> "";
    };
  }

  public static List<String> split(String stored) {
    return stored == null || stored.isBlank() ? List.of() : Arrays.asList(stored.split(","));
  }
}
