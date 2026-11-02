package az.azertur.cancellation;

import az.azertur.customer.CustomerAuthService.Customer;
import az.azertur.shared.DomainException;
import java.io.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
public class CancellationSummaryController {
 private final CancellationPortal portal;private final ObjectMapper json;
 public CancellationSummaryController(CancellationPortal portal,ObjectMapper json){this.portal=portal;this.json=json;}
 @GetMapping("/api/v1/bookings/{id}/cancellation-updates/{request}/pdf")
 public ResponseEntity<byte[]> pdf(@PathVariable UUID id,@PathVariable UUID request,@AuthenticationPrincipal Customer customer) throws IOException {
  var updates=json.valueToTree(portal.updates(id,customer.id()));tools.jackson.databind.JsonNode selected=null;
  for(var u:updates)if(u.path("id").asText().equals(request.toString())&&u.has("lines"))selected=u;
  if(selected==null)throw DomainException.missing();
  var lines=new ArrayList<String>();
  lines.add("AZERTUR — CANCELLATION SUMMARY");lines.add("Booking: "+id);lines.add("Request: "+request);
  lines.add("Approved: "+selected.path("approved_at").asText());
  lines.add("This summary records the cancellation decision. It is not proof of a refund transfer.");
  String currency=selected.path("currency").asText();
  lines.add("Paid / retained / refund due ("+currency+"):");
  for(var line:selected.path("lines")) {
   var c=line.path("calculation");
   lines.add(line.path("serviceKey").asText()+": "+amount(c.path("paidMinor").asLong())+" / "+amount(c.path("retainedMinor").asLong())+" / "+amount(c.path("refundMinor").asLong()));
   lines.add(line.path("policyText").asText());
  }
  lines.add("Refund due: "+amount(selected.path("refundMinor").asLong())+" "+currency);
  lines.add(selected.path("refundStatus").asText().equals("NOT_REQUIRED")?"No refund is due.":"Refund pending. Funds have not been transferred.");
  lines.add(selected.path("customer_explanation").asText(""));
  byte[] bytes;
  try(var document=new PDDocument();var fontStream=new ClassPathResource("fonts/inter-400.ttf").getInputStream();var out=new ByteArrayOutputStream()) {
   var font=PDType0Font.load(document,fontStream);
   PDPageContentStream stream=null;float y=0;
   try {
    for(String paragraph:lines) {
     StringBuilder safe=new StringBuilder();
     for(int cp:paragraph.replace("\n"," ").replace("\r"," ").codePoints().toArray()) {String ch=new String(Character.toChars(cp));try{font.encode(ch);safe.append(ch);}catch(IllegalArgumentException e){safe.append('?');}}
     var wrapped=new ArrayList<String>();String current="";
     for(String word:safe.toString().replace("\n"," ").split(" ")) {
      if(font.getStringWidth(current+word)*10/1000>490&&!current.isEmpty()){wrapped.add(current);current="";}
      while(font.getStringWidth(word)*10/1000>490&&word.length()>1){int cut=Math.min(70,word.length());while(cut>1 && font.getStringWidth(word.substring(0,cut))*10/1000>490)cut--;wrapped.add(word.substring(0,cut));word=word.substring(cut);}
      current+=word+" ";
     }
     wrapped.add(current);
     for(String line:wrapped) {
      if(stream==null||y<65){if(stream!=null)stream.close();var page=new PDPage(PDRectangle.A4);document.addPage(page);stream=new PDPageContentStream(document,page);y=780;}
      stream.beginText();stream.setFont(font,10);stream.newLineAtOffset(50,y);stream.showText(line);stream.endText();y-=15;
     }
     y-=10;
    }
   }finally{if(stream!=null)stream.close();}
   document.save(out);bytes=out.toByteArray();
  }
  return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("Content-Disposition","attachment; filename=cancellation-summary.pdf").contentType(MediaType.APPLICATION_PDF).body(bytes);
 }
 private String amount(long minor){return java.math.BigDecimal.valueOf(minor,2).toPlainString();}
}
