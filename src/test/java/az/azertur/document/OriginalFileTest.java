package az.azertur.document;
import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.Test;
class OriginalFileTest {
 @Test void originalPdfIsAcceptedWithoutTransformation()throws Exception{try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()){pdf.addPage(new PDPage());pdf.save(out);assertEquals("application/pdf",OriginalFile.validate(out.toByteArray()));}}
 @Test void actualImageBytesDetermineFormat()throws Exception{for(String format:new String[]{"png","jpeg"}){var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(3,3,BufferedImage.TYPE_INT_RGB),format,out);assertEquals("image/"+format,OriginalFile.validate(out.toByteArray()));}}
 @Test void rejectsSpoofedOrMalformedDocuments(){assertThrows(RuntimeException.class,()->OriginalFile.validate("<svg onload='alert(1)'/>".getBytes()));assertThrows(RuntimeException.class,()->OriginalFile.validate("%PDF-1.7 broken".getBytes()));assertThrows(RuntimeException.class,()->OriginalFile.validate(new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff,0}));}
 @Test void enforcesTenMegabytes(){assertThrows(RuntimeException.class,()->OriginalFile.validate(new byte[10_000_001]));assertThrows(RuntimeException.class,()->OriginalFile.validate(new byte[0]));}
 @Test void rejectsActivePdf()throws Exception{try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()){pdf.addPage(new PDPage());pdf.getDocumentCatalog().setOpenAction(new org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript("app.alert('test')"));pdf.save(out);assertThrows(RuntimeException.class,()->OriginalFile.validate(out.toByteArray()));}}
}
