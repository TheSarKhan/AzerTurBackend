package az.azertur.document;

import az.azertur.shared.DomainException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;

/** Validate actual bytes, not the supplied filename or content type. Never creates boarding credentials. */
public final class OriginalFile {
  private OriginalFile() {}
  public static String validate(byte[] bytes) {
    if(bytes.length==0 || bytes.length>10_000_000) throw DomainException.bad("DOCUMENT_SIZE_LIMIT");
    if(bytes.length>8 && new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-")) {
      try(var pdf=Loader.loadPDF(bytes)) {
        if(pdf.isEncrypted() || pdf.getNumberOfPages()<1 || pdf.getNumberOfPages()>100) throw DomainException.bad("INVALID_DOCUMENT");
        if(pdf.getDocumentCatalog().getCOSObject().containsKey(org.apache.pdfbox.cos.COSName.OPEN_ACTION)
            || pdf.getDocumentCatalog().getCOSObject().containsKey(org.apache.pdfbox.cos.COSName.AA)) throw DomainException.bad("ACTIVE_PDF_NOT_ALLOWED");
        return "application/pdf";
      } catch(IOException e) { throw DomainException.bad("INVALID_DOCUMENT"); }
    }
    String mime;
    if(bytes.length>8 && bytes[0]==(byte)0x89 && bytes[1]=='P' && bytes[2]=='N' && bytes[3]=='G' && bytes[4]==13 && bytes[5]==10 && bytes[6]==26 && bytes[7]==10) mime="image/png";
    else if(bytes.length>3 && bytes[0]==(byte)0xff && bytes[1]==(byte)0xd8 && bytes[2]==(byte)0xff) mime="image/jpeg";
    else throw DomainException.bad("DOCUMENT_FORMAT_NOT_SUPPORTED");
    try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers=ImageIO.getImageReaders(stream);
      if(!readers.hasNext()) throw DomainException.bad("INVALID_DOCUMENT");
      var reader=readers.next();
      try {
        reader.setInput(stream);
        int w=reader.getWidth(0), h=reader.getHeight(0);
        if(w<1 || h<1 || w>16000 || h>16000 || (long)w*h>40_000_000) throw DomainException.bad("DOCUMENT_IMAGE_TOO_LARGE");
        if(reader.read(0)==null) throw DomainException.bad("INVALID_DOCUMENT");
      } finally { reader.dispose(); }
      return mime;
    } catch(IOException|IllegalArgumentException e) {throw DomainException.bad("INVALID_DOCUMENT");}
  }
}
