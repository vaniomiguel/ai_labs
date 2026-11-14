package pt.conta.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import pt.conta.shared.DomainException;
import pt.conta.shared.Json;

/** Assembles complete, text-only invoice requests shared by automatic extraction and repairs. */
@Component
public class InvoiceExtractionRequests {
  private static final int AUTOMATIC_MAX_INPUT_CHARACTERS = 50000;
  private final PromptCatalog prompts;

  public InvoiceExtractionRequests(PromptCatalog prompts) {
    this.prompts = prompts;
  }

  public AiProvider.Request automatic(String model, String sourceText) {
    return assemble(model, sourceText, Map.of(), AUTOMATIC_MAX_INPUT_CHARACTERS);
  }

  /** Keeps saved repair context as data, without changing the authoritative OCR text. */
  public AiProvider.Request repair(
      String model, String sourceText, Map<String, Object> context, int maxInputCharacters) {
    return assemble(model, sourceText, context, maxInputCharacters);
  }

  private AiProvider.Request assemble(
      String model, String sourceText, Map<String, Object> context, int maxInputCharacters) {
    if (sourceText == null || sourceText.isBlank())
      throw DomainException.invalid("Não existe texto extraído da fatura para analisar; revê a extração OCR.");

    String instructions = prompts.prompt(PromptCatalog.INVOICE_EXTRACTION_SYSTEM_INSTRUCTIONS);
    var payload = new LinkedHashMap<>(context);
    payload.put("fields", prompts.prompt(PromptCatalog.INVOICE_EXTRACTION_FIELD_INSTRUCTIONS));
    payload.put("sourceText", sourceText);
    String input = Json.write(payload);
    var schema = prompts.schema(PromptCatalog.INVOICE_EXTRACTION_SYSTEM_INSTRUCTIONS);
    long inputCharacters = (long) instructions.length() + input.length() + Json.write(schema).length();
    if (inputCharacters > maxInputCharacters)
      throw new AiProvider.ContextExceeded(
          "O texto integral e as instruções excedem o contexto configurado desta análise. "
              + "Seleciona uma configuração com maior capacidade ou revê a fatura manualmente. "
              + "O conteúdo não foi cortado.");

    return new AiProvider.Request(model, instructions, input, schema, List.of(), true);
  }
}
