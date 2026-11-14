package az.azertur.contract;

import az.azertur.offer.OfferService.PublicLine;
import az.azertur.offer.OfferService.PublicOffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Builds the travel services agreement as a PDF from a sent offer, in the customer's language. The
 * wording is a standard template: the company details come from the configuration and the legal text
 * should be reviewed by the company's lawyer before it is relied on.
 */
@Component
public class ContractPdf {
  public record Company(String name, String voen, String address, String phone, String email, String bank, String signatory) {}

  public record Input(String number, String date, String locale, String customerName, String customerEmail, String customerPhone, PublicOffer offer) {}

  private final Company company;

  public ContractPdf(
      @Value("${app.company.name:AzTrip}") String name,
      @Value("${app.company.voen:}") String voen,
      @Value("${app.company.address:}") String address,
      @Value("${app.company.phone:}") String phone,
      @Value("${app.company.email:}") String email,
      @Value("${app.company.bank:}") String bank,
      @Value("${app.company.signatory:}") String signatory) {
    this.company = new Company(name, voen, address, phone, email, bank, signatory);
  }

  private static final Map<String, Map<String, String>> TEXT =
      Map.of(
          "az",
          Map.ofEntries(
              Map.entry("title", "Səyahət xidmətləri müqaviləsi"),
              Map.entry("no", "Müqavilə №"),
              Map.entry("parties", "1. Tərəflər"),
              Map.entry("provider", "Xidmət göstərən (Şirkət)"),
              Map.entry("customer", "Müştəri"),
              Map.entry("voen", "VÖEN"),
              Map.entry("address", "Ünvan"),
              Map.entry("phone", "Telefon"),
              Map.entry("email", "Email"),
              Map.entry("bank", "Bank rekvizitləri"),
              Map.entry("signatory", "Təmsilçi"),
              Map.entry("subject", "2. Müqavilənin predmeti"),
              Map.entry("subjectText", "Şirkət Müştəriyə aşağıda göstərilən səyahət xidmətlərini göstərməyi, Müştəri isə bu xidmətlərin haqqını ödəməyi öhdəsinə götürür. Xidmətlərin tərkibi və qiyməti Müştəriyə göndərilmiş və təsdiqlənmiş təklifə uyğundur."),
              Map.entry("trip", "3. Səfər məlumatları"),
              Map.entry("tripTitle", "Səfərin adı"),
              Map.entry("date", "Başlama tarixi"),
              Map.entry("travellers", "Səyahətçilərin sayı"),
              Map.entry("route", "Marşrut"),
              Map.entry("start", "Başlanğıc"),
              Map.entry("day", "Gün"),
              Map.entry("end", "Qayıdış"),
              Map.entry("services", "4. Xidmətlər və qiymət"),
              Map.entry("total", "Qrup üçün ümumi məbləğ"),
              Map.entry("payment", "5. Ödəniş qaydası"),
              Map.entry("paymentText", "Təklif və müqavilə Müştəri tərəfindən təsdiqləndikdən sonra Şirkət beh məbləği tələb edir. Qalan ödənişlər xidmətlərə uyğun olaraq mərhələlərlə istənilir. Müştərinin ödənişləri Şirkətin ofisi tərəfindən qeydə alınır və Müştərinin şəxsi kabinetində görünür."),
              Map.entry("cancel", "6. Dəyişiklik və ləğv şərtləri"),
              Map.entry("cancelText", "Dəyişiklik və ləğv sorğuları Müştərinin şəxsi kabineti vasitəsilə göndərilir və Şirkətin ofisi tərəfindən hər xidmətin təchizatçısının şərtlərinə və sorğunun vaxtına görə ayrıca nəzərdən keçirilir. Geri qaytarılacaq məbləğ həmin şərtlərə uyğun müəyyən edilir."),
              Map.entry("terms", "Xidmətlər üzrə şərtlər"),
              Map.entry("duties", "7. Tərəflərin öhdəlikləri"),
              Map.entry("dutiesText", "Müştəri səyahətçilər haqqında düzgün məlumat verməyi və lazım olan sənədlərin (pasport, viza və s.) hazırlığına məsuliyyət daşımağı öhdəsinə götürür. Şirkət xidmətləri təklifdə göstərilən tərkibdə və keyfiyyətdə təşkil etməyi öhdəsinə götürür. Tərəflər qarşısıalınmaz hallar (təbii fəlakət, yolların bağlanması, rəsmi qadağalar) səbəbindən öhdəliklərin yerinə yetirilməməsinə görə məsuliyyət daşımırlar; bu halda xidmətlərin dəyişdirilməsi və ya ləğvi bu müqavilənin 6-cı bəndinə uyğun həll olunur."),
              Map.entry("accept", "8. Təsdiq"),
              Map.entry("acceptText", "Müştəri bu müqaviləni şəxsi kabinetində “Təsdiq edirəm” düyməsi ilə elektron qaydada təsdiq edir. Təsdiqin vaxtı və bu PDF faylının SHA-256 xülasəsi sistemdə saxlanılır və imza əvəzinə keçir."),
              Map.entry("signCompany", "Şirkət"),
              Map.entry("signCustomer", "Müştəri"),
              Map.entry("page", "Səhifə"),
              Map.entry("perUnit", "x")),
          "en",
          Map.ofEntries(
              Map.entry("title", "Travel services agreement"),
              Map.entry("no", "Agreement No."),
              Map.entry("parties", "1. Parties"),
              Map.entry("provider", "Service provider (the Company)"),
              Map.entry("customer", "Customer"),
              Map.entry("voen", "Tax ID (VÖEN)"),
              Map.entry("address", "Address"),
              Map.entry("phone", "Phone"),
              Map.entry("email", "Email"),
              Map.entry("bank", "Bank details"),
              Map.entry("signatory", "Representative"),
              Map.entry("subject", "2. Subject of the agreement"),
              Map.entry("subjectText", "The Company undertakes to provide the Customer with the travel services listed below, and the Customer undertakes to pay for them. The services and their prices correspond to the offer sent to and approved by the Customer."),
              Map.entry("trip", "3. Trip details"),
              Map.entry("tripTitle", "Trip"),
              Map.entry("date", "Start date"),
              Map.entry("travellers", "Number of travellers"),
              Map.entry("route", "Route"),
              Map.entry("start", "Start"),
              Map.entry("day", "Day"),
              Map.entry("end", "Return"),
              Map.entry("services", "4. Services and price"),
              Map.entry("total", "Group total"),
              Map.entry("payment", "5. Payment"),
              Map.entry("paymentText", "After the Customer approves the offer and this agreement, the Company requests a deposit. The remaining payments are requested in stages, following the services. The Company's office records the Customer's payments and they appear in the Customer's account."),
              Map.entry("cancel", "6. Changes and cancellation"),
              Map.entry("cancelText", "Requests for changes and cancellation are sent through the Customer's account and are reviewed by the Company's office individually, according to each supplier's terms and the time of the request. Any refund is determined under those terms."),
              Map.entry("terms", "Terms by service"),
              Map.entry("duties", "7. Obligations of the parties"),
              Map.entry("dutiesText", "The Customer undertakes to give correct information about the travellers and to be responsible for the documents required (passport, visa and so on). The Company undertakes to arrange the services in the scope and quality described in the offer. Neither party is liable for failure to perform caused by force majeure (natural disaster, closed roads, official bans); changes or cancellation in that case are handled under clause 6."),
              Map.entry("accept", "8. Approval"),
              Map.entry("acceptText", "The Customer approves this agreement electronically in their account with the “Approve” button. The time of approval and the SHA-256 hash of this PDF file are stored in the system and take the place of a signature."),
              Map.entry("signCompany", "Company"),
              Map.entry("signCustomer", "Customer"),
              Map.entry("page", "Page"),
              Map.entry("perUnit", "x")),
          "ru",
          Map.ofEntries(
              Map.entry("title", "Договор на туристические услуги"),
              Map.entry("no", "Договор №"),
              Map.entry("parties", "1. Стороны"),
              Map.entry("provider", "Исполнитель (Компания)"),
              Map.entry("customer", "Клиент"),
              Map.entry("voen", "ИНН (VÖEN)"),
              Map.entry("address", "Адрес"),
              Map.entry("phone", "Телефон"),
              Map.entry("email", "Email"),
              Map.entry("bank", "Банковские реквизиты"),
              Map.entry("signatory", "Представитель"),
              Map.entry("subject", "2. Предмет договора"),
              Map.entry("subjectText", "Компания обязуется оказать Клиенту перечисленные ниже туристические услуги, а Клиент обязуется оплатить их. Состав и цена услуг соответствуют предложению, отправленному и одобренному Клиентом."),
              Map.entry("trip", "3. Сведения о поездке"),
              Map.entry("tripTitle", "Поездка"),
              Map.entry("date", "Дата начала"),
              Map.entry("travellers", "Количество путешественников"),
              Map.entry("route", "Маршрут"),
              Map.entry("start", "Старт"),
              Map.entry("day", "День"),
              Map.entry("end", "Возвращение"),
              Map.entry("services", "4. Услуги и цена"),
              Map.entry("total", "Итого для группы"),
              Map.entry("payment", "5. Порядок оплаты"),
              Map.entry("paymentText", "После одобрения Клиентом предложения и договора Компания запрашивает депозит. Остальные платежи запрашиваются поэтапно в соответствии с услугами. Офис Компании фиксирует платежи Клиента, и они отображаются в его кабинете."),
              Map.entry("cancel", "6. Изменение и отмена"),
              Map.entry("cancelText", "Запросы на изменение и отмену отправляются через кабинет Клиента и рассматриваются офисом Компании индивидуально, с учётом условий поставщика каждой услуги и времени запроса. Сумма возврата определяется по этим условиям."),
              Map.entry("terms", "Условия по услугам"),
              Map.entry("duties", "7. Обязанности сторон"),
              Map.entry("dutiesText", "Клиент обязуется предоставить достоверные сведения о путешественниках и отвечает за необходимые документы (паспорт, виза и т. д.). Компания обязуется организовать услуги в составе и качестве, указанных в предложении. Стороны не отвечают за неисполнение обязательств вследствие форс-мажора (стихийные бедствия, закрытие дорог, официальные запреты); изменение или отмена в этом случае решается по пункту 6."),
              Map.entry("accept", "8. Подтверждение"),
              Map.entry("acceptText", "Клиент подтверждает договор в электронном виде в своём кабинете кнопкой «Подтверждаю». Время подтверждения и SHA-256 этого PDF-файла сохраняются в системе и заменяют подпись."),
              Map.entry("signCompany", "Компания"),
              Map.entry("signCustomer", "Клиент"),
              Map.entry("page", "Стр."),
              Map.entry("perUnit", "x")));

  private static final Map<String, String[]> CITIES =
      Map.of(
          "Baku", new String[] {"Baku", "Баку", "Bakı"},
          "Quba", new String[] {"Quba", "Куба", "Quba"},
          "Khinalug", new String[] {"Khinalug", "Хыналыг", "Xınalıq"},
          "Gabala", new String[] {"Gabala", "Габала", "Qəbələ"},
          "Sheki", new String[] {"Sheki", "Шеки", "Şəki"});

  private static String city(String key, String locale) {
    var names = CITIES.get(key);
    if (names == null) return key;
    return locale.equals("az") ? names[2] : locale.equals("ru") ? names[1] : names[0];
  }

  public byte[] build(Input in) throws IOException {
    String locale = TEXT.containsKey(in.locale()) ? in.locale() : "en";
    Map<String, String> t = TEXT.get(locale);
    var offer = in.offer();
    try (var doc = new Doc(); var out = new ByteArrayOutputStream()) {
      doc.text(t.get("title"), 18, 0);
      doc.gap(4);
      doc.text(t.get("no") + " " + in.number() + ", " + in.date(), 10.5f, 0);
      doc.gap(14);

      doc.heading(t.get("parties"));
      doc.kv(t.get("provider"), company.name());
      doc.kv(t.get("voen"), company.voen());
      doc.kv(t.get("address"), company.address());
      doc.kv(t.get("phone"), company.phone());
      doc.kv(t.get("email"), company.email());
      doc.kv(t.get("bank"), company.bank());
      doc.kv(t.get("signatory"), company.signatory());
      doc.gap(8);
      doc.kv(t.get("customer"), in.customerName());
      doc.kv(t.get("email"), in.customerEmail());
      doc.kv(t.get("phone"), in.customerPhone());

      doc.heading(t.get("subject"));
      doc.para(t.get("subjectText"), 10.5f);

      doc.heading(t.get("trip"));
      doc.kv(t.get("tripTitle"), offer.title());
      doc.kv(t.get("date"), offer.date());
      doc.kv(t.get("travellers"), String.valueOf(offer.travelers()));
      doc.gap(4);
      doc.text(t.get("route") + ":", 10.5f, 0);
      var places = offer.itinerary();
      String start = offer.startCity() == null ? "Baku" : offer.startCity();
      String end = offer.endCity() == null ? "Baku" : offer.endCity();
      doc.para(t.get("start") + ": " + city(start, locale), 10.5f, 14);
      int lastDay = -1;
      for (int i = 0; i < places.size(); i++) {
        var p = places.get(i);
        int day = p.day() == null ? i + 1 : p.day();
        String label = (day != lastDay ? t.get("day") + " " + day + ": " : "      ") + city(p.city(), locale);
        String d = p.description() == null ? "" : p.description().trim();
        if (!d.isEmpty() && !d.equalsIgnoreCase(p.city())) label += ", " + d;
        doc.para(label, 10.5f, 14);
        lastDay = day;
      }
      doc.para(t.get("end") + ": " + city(end, locale), 10.5f, 14);

      doc.heading(t.get("services"));
      int count = places.size();
      for (int row = 0; row <= count + 1; row++) {
        final int r = row;
        List<PublicLine> lines =
            offer.services().stream().filter(l -> rowOf(l, count) == r).toList();
        if (lines.isEmpty()) continue;
        String where =
            row == 0
                ? t.get("start") + ", " + city(start, locale)
                : row == count + 1
                    ? t.get("end") + ", " + city(end, locale)
                    : t.get("day") + " " + (places.get(row - 1).day() == null ? row : places.get(row - 1).day())
                        + ", " + city(places.get(row - 1).city(), locale);
        doc.gap(4);
        doc.text(where, 10.5f, 0);
        for (var l : lines)
          doc.split(
              l.name(),
              l.quantity() + (l.unit().equals("room") ? " x " + l.nights() : "") + " x " + unit(l.unitPrice()),
              money(BigDecimal.valueOf(l.total())) + " " + offer.currency(),
              10);
      }
      doc.gap(8);
      doc.split(t.get("total"), "", money(BigDecimal.valueOf(offer.total())) + " " + offer.currency(), 11.5f);

      doc.heading(t.get("payment"));
      doc.para(t.get("paymentText"), 10.5f);

      doc.heading(t.get("cancel"));
      doc.para(t.get("cancelText"), 10.5f);
      var policies = new LinkedHashSet<String>();
      for (var l : offer.services()) if (l.policy() != null && !l.policy().isBlank()) policies.add(l.policy().trim());
      if (!policies.isEmpty()) {
        doc.gap(4);
        doc.text(t.get("terms") + ":", 10.5f, 0);
        for (String p : policies) doc.para("- " + p, 10, 10);
      }

      doc.heading(t.get("duties"));
      doc.para(t.get("dutiesText"), 10.5f);

      doc.heading(t.get("accept"));
      doc.para(t.get("acceptText"), 10.5f);
      doc.gap(16);
      doc.ensure(70);
      doc.text(t.get("signCompany") + ": " + (company.signatory().isBlank() ? "______________________" : company.signatory()), 10.5f, 0);
      doc.gap(8);
      doc.text(t.get("signCustomer") + ": " + in.customerName(), 10.5f, 0);

      doc.finish(t.get("page"), company.name());
      doc.save(out);
      return out.toByteArray();
    }
  }

  private static int rowOf(PublicLine l, int count) {
    int row = l.row() != null ? l.row() : (l.key().replaceAll("-[0-9]+$", "").startsWith("transfer") ? 0 : 1);
    return Math.min(Math.max(row, 0), count + 1);
  }

  /** A price per unit to two decimals, without trailing zeros: 89.999 becomes 90, 51.6695 becomes 51.67. */
  private static String unit(BigDecimal value) {
    return value.setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
  }

  private static String money(BigDecimal value) {
    return value.stripTrailingZeros().scale() < 0 ? value.setScale(0).toPlainString() : value.stripTrailingZeros().toPlainString();
  }

  /** A small writer over PDFBox: wrapped paragraphs, label and value rows, page breaks and page numbers. */
  private static final class Doc implements AutoCloseable {
    private static final float LEFT = 54, RIGHT = 541, TOP = 790, BOTTOM = 60;
    private final PDDocument doc = new PDDocument();
    private final PDType0Font font;
    private final List<PDPage> pages = new ArrayList<>();
    private PDPageContentStream s;
    private float y;

    Doc() throws IOException {
      try (var in = new ClassPathResource("fonts/inter-400.ttf").getInputStream()) {
        font = PDType0Font.load(doc, in);
      }
    }

    private String safe(String raw) {
      var out = new StringBuilder();
      for (int cp : (raw == null ? "" : raw).replace("\n", " ").replace("\r", " ").codePoints().toArray()) {
        String ch = new String(Character.toChars(cp));
        try {
          font.encode(ch);
          out.append(ch);
        } catch (IllegalArgumentException | IOException e) {
          out.append('?');
        }
      }
      return out.toString();
    }

    private float width(String text, float size) throws IOException {
      return font.getStringWidth(text) / 1000f * size;
    }

    void newPage() throws IOException {
      if (s != null) s.close();
      var page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      pages.add(page);
      s = new PDPageContentStream(doc, page);
      y = TOP;
    }

    void ensure(float need) throws IOException {
      if (s == null || y - need < BOTTOM) newPage();
    }

    void gap(float h) {
      y -= h;
    }

    void text(String text, float size, float indent) throws IOException {
      ensure(size + 6);
      s.beginText();
      s.setFont(font, size);
      s.newLineAtOffset(LEFT + indent, y);
      s.showText(safe(text));
      s.endText();
      y -= size + 5;
    }

    void heading(String text) throws IOException {
      gap(10);
      ensure(40);
      text(text, 12.5f, 0);
      gap(2);
    }

    void para(String text, float size) throws IOException {
      para(text, size, 0);
    }

    void para(String text, float size, float indent) throws IOException {
      float max = RIGHT - LEFT - indent;
      String current = "";
      for (String word : safe(text).split(" ")) {
        String next = current.isEmpty() ? word : current + " " + word;
        if (width(next, size) > max && !current.isEmpty()) {
          text(current, size, indent);
          current = word;
        } else current = next;
      }
      if (!current.isEmpty()) text(current, size, indent);
    }

    void kv(String label, String value) throws IOException {
      String v = value == null || value.isBlank() ? "______________________" : value;
      ensure(18);
      s.beginText();
      s.setFont(font, 10);
      s.newLineAtOffset(LEFT, y);
      s.showText(safe(label));
      s.endText();
      s.beginText();
      s.setFont(font, 10.5f);
      s.newLineAtOffset(LEFT + 150, y);
      s.showText(safe(v));
      s.endText();
      y -= 15;
    }

    /** Name on the left, a middle column, an amount flush right. */
    void split(String left, String middle, String right, float size) throws IOException {
      ensure(size + 8);
      String name = safe(left);
      float room = middle.isEmpty() ? 380 : 280;
      while (name.length() > 1 && width(name, size) > room) name = name.substring(0, name.length() - 1);
      if (!name.equals(safe(left))) name = name.substring(0, Math.max(0, name.length() - 1)) + "...";
      s.beginText();
      s.setFont(font, size);
      s.newLineAtOffset(LEFT + 10, y);
      s.showText(name);
      s.endText();
      if (!middle.isEmpty()) {
        s.beginText();
        s.setFont(font, size - 0.5f);
        s.newLineAtOffset(LEFT + 300, y);
        s.showText(safe(middle));
        s.endText();
      }
      String r = safe(right);
      s.beginText();
      s.setFont(font, size);
      s.newLineAtOffset(RIGHT - width(r, size), y);
      s.showText(r);
      s.endText();
      y -= size + 5;
    }

    void finish(String pageWord, String name) throws IOException {
      if (s != null) s.close();
      s = null;
      for (int i = 0; i < pages.size(); i++) {
        try (var c = new PDPageContentStream(doc, pages.get(i), PDPageContentStream.AppendMode.APPEND, true, true)) {
          String footer = safe(name + ", " + pageWord + " " + (i + 1) + "/" + pages.size());
          c.beginText();
          c.setFont(font, 8.5f);
          c.newLineAtOffset(LEFT, 36);
          c.showText(footer);
          c.endText();
        }
      }
    }

    void save(ByteArrayOutputStream out) throws IOException {
      doc.save(out);
    }

    @Override
    public void close() throws IOException {
      if (s != null) s.close();
      doc.close();
    }
  }
}
