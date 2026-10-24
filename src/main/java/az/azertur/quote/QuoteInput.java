package az.azertur.quote;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
public record QuoteInput(@NotBlank @Size(max=80) String slug,@NotNull LocalDate date,@Min(1) @Max(12) int pax,
 @NotNull @Pattern(regexp="standard|comfort|premium") String hotel,Boolean driver,Boolean stay,boolean guide,
 @Pattern(regexp="en|ru") String guideLanguage,boolean transfer,boolean activity,
 @NotNull @Pattern(regexp="EUR|USD|AZN") String currency,@Min(1) @Max(14) Integer customDays,@Size(min=1,max=14) List<@NotBlank String> cities){}
