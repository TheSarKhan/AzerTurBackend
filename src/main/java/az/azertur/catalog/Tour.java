package az.azertur.catalog;

import jakarta.persistence.*;

@Entity
@Table(name = "tours")
public class Tour {
  @Id public String slug;
  public String titleEn;
  public String titleRu;
  public String titleAz;
  public String region;
  public int days;
  public int fromPriceEur;
  public String image;
  public Double latitude;
  public Double longitude;
  /** Comma separated, see {@link TourCategories}. */
  public String categories = "";

  protected Tour() {}

  public Tour(
      String slug,
      String titleEn,
      String titleRu,
      String titleAz,
      String region,
      int days,
      int fromPriceEur,
      String image) {
    this.slug = slug;
    this.titleEn = titleEn;
    this.titleRu = titleRu;
    this.titleAz = titleAz;
    this.region = region;
    this.days = days;
    this.fromPriceEur = fromPriceEur;
    this.image = image;
    this.categories = TourCategories.forImage(image);
  }
}
