package az.azertur.catalog;
import jakarta.persistence.*;
@Entity @Table(name="tours")
public class Tour {
 @Id public String slug;
 public String titleEn; public String titleRu; public String region; public int days; public int fromPriceEur; public String image;
 protected Tour(){}
}
