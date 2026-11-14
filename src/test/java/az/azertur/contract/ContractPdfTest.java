package az.azertur.contract;

import static org.junit.jupiter.api.Assertions.*;

import az.azertur.offer.OfferService.Day;
import az.azertur.offer.OfferService.PublicLine;
import az.azertur.offer.OfferService.PublicOffer;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ContractPdfTest {
  private final ContractPdf generator =
      new ContractPdf("AzTrip MMC", "1234567890", "Baku, Azerbaijan", "+994 12 000 00 00", "office@aztrip.example", "", "Ali Aliyev");

  private PublicOffer offer() {
    return new PublicOffer(
        UUID.randomUUID(), UUID.randomUUID(), 1, 3, "Your custom journey", "EUR", "2027-03-10", 2,
        List.of(new Day("Quba", "Quba", 1), new Day("Khinalug", "Village walk and lunch", 1), new Day("Sheki", "Sheki", 2)),
        "",
        List.of(
            new PublicLine("transfer", "Airport transfer", 1, 1, new BigDecimal("52"), "transfer", 52, "Free cancellation up to 24 hours before landing.", 0),
            new PublicLine("guide", "Local guide, Quba", 1, 1, new BigDecimal("65"), "day", 65, "Free cancellation up to 3 days before.", 1),
            new PublicLine("hotel", "Comfort room, Sheki", 1, 1, new BigDecimal("110"), "room", 110, "Free cancellation up to 7 days before arrival.", 3)),
        227, "Hello", "2026-11-14T10:00:00Z", "Baku", "Baku");
  }

  private String text(String locale, String name) throws Exception {
    byte[] pdf = generator.build(new ContractPdf.Input("AB12CD34-1", "2026-11-14", locale, name, "customer@example.com", "+994501234567", offer()));
    try (var doc = Loader.loadPDF(pdf)) {
      assertTrue(doc.getNumberOfPages() >= 1);
      return new PDFTextStripper().getText(doc);
    }
  }

  @Test
  void azerbaijaniLettersSurviveInTheText() throws Exception {
    String t = text("az", "Əli Həsənov");
    assertTrue(t.contains("Səyahət xidmətləri müqaviləsi"), t);
    assertTrue(t.contains("Əli Həsənov"));
    assertTrue(t.contains("Xınalıq") && t.contains("Şəki") && t.contains("Qəbələ") == false);
    assertFalse(t.contains("?"), "no glyph was replaced by a question mark");
  }

  @Test
  void russianAndEnglishAreBuiltToo() throws Exception {
    String ru = text("ru", "Иван Петров");
    assertTrue(ru.contains("Договор на туристические услуги") && ru.contains("Иван Петров"));
    String en = text("en", "John Smith");
    assertTrue(en.contains("Travel services agreement"));
    assertTrue(en.contains("227 EUR") && en.contains("Airport transfer"));
  }

  @Test
  void companyDetailsAndEveryPlaceAppear() throws Exception {
    String t = text("en", "John Smith");
    assertTrue(t.contains("AzTrip MMC") && t.contains("1234567890") && t.contains("Ali Aliyev"));
    assertTrue(t.contains("Day 1: Quba") && t.contains("Village walk and lunch") && t.contains("Day 2: Sheki"));
    assertTrue(t.contains("Start: Baku") && t.contains("Return: Baku"));
  }
}
