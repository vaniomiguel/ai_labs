package pt.conta.document;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import pt.conta.shared.*;

/** Reads native PDF text before delegating damaged or scanned pages to the OCR service. */
@Component
public class PdfDocumentConverter implements DocumentConverter {
  private final RestClient client;

  public PdfDocumentConverter(AccountingProperties properties) {
    var factory = new JdkClientHttpRequestFactory();
    factory.setReadTimeout(Duration.ofMinutes(10));
    client =
        RestClient.builder()
            .baseUrl(properties.documentWorkerUrl())
            .requestFactory(factory)
            .build();
  }

  /** Retains a usable native result if OCR is offline; the extraction stage decides eligibility. */
  @Override
  public Converted convert(Path path) throws Exception {
    String text;
    int pages;
    var pageTexts = new ArrayList<Map<String, Object>>();
    try (var pdf = Loader.loadPDF(path.toFile())) {
      pages = pdf.getNumberOfPages();
      if (pages > 1000) throw DomainException.invalid("O PDF excede o limite de 1000 páginas");
      var stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      for (int page = 1; page <= pages; page++) {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        pageTexts.add(Inputs.map("page", page, "text", stripper.getText(pdf)));
      }
      text =
          pageTexts.stream()
              .map(p -> p.get("text").toString())
              .collect(java.util.stream.Collectors.joining("\n"));
    }
    boolean damaged =
        pageTexts.stream()
                .anyMatch(p -> p.get("text").toString().replaceAll("\\s", "").length() < 30)
            || text.indexOf('\uFFFD') >= 0;
    if (!damaged)
      return new Converted(
          text,
          pages,
          Inputs.map(
              "pages",
              pageTexts,
              "tables",
              List.of(),
              "version",
              org.apache.pdfbox.pdmodel.PDDocument.class.getPackage().getImplementationVersion()),
          "PDFBOX");
    try {
      var form = new LinkedMultiValueMap<String, Object>();
      form.add("file", new FileSystemResource(path));
      String result =
          client
              .post()
              .uri("/convert")
              .contentType(MediaType.MULTIPART_FORM_DATA)
              .body(form)
              .retrieve()
              .body(String.class);
      var json = Json.object(result);
      int count = ((Number) json.getOrDefault("pages", pages)).intValue();
      json.put("pages", json.getOrDefault("page_items", List.of()));
      return new Converted(
          Objects.toString(json.get("text"), ""), count, json, "DOCLING_TESSERACT");
    } catch (Exception e) {
      return new Converted(
          text,
          pages,
          Inputs.map(
              "pages",
              pageTexts,
              "tables",
              List.of(),
              "conversionError",
              "OCR indisponível: " + e.getClass().getSimpleName()),
          "PDFBOX_PARTIAL");
    }
  }
}
