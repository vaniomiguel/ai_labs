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
  private final InvoiceRepairContext repairContext;

  public InvoiceExtractionRequests(PromptCatalog prompts, InvoiceRepairContext repairContext) {
    this.prompts = prompts;
    this.repairContext = repairContext;
  }

  public AiProvider.Request automatic(String model, String sourceText) {
    return assemble(model, sourceText, Map.of(), PromptCatalog.INVOICE_EXTRACTION_SYSTEM_INSTRUCTIONS,
        AUTOMATIC_MAX_INPUT_CHARACTERS);
  }

  /** Keeps saved repair context as data, without changing the authoritative OCR text. */
  public AiProvider.Request repair(
      String model, String sourceText, Map<String, Object> context, int maxInputCharacters) {
    var schema = prompts.schema(PromptCatalog.INVOICE_EXTRACTION_SYSTEM_INSTRUCTIONS);
    var state = repairContext.build(
        savedObject(context.get("data")), savedObject(context.get("fieldProvenance")), schema);
    var payload = new LinkedHashMap<>(context);
    payload.put("fieldsToExtract", state.fieldsToExtract());
    payload.put("filledFields", state.filledFields());
    payload.put("responseSchema", schema);
    payload.put("responseTemplate", state.responseTemplate());
    return assemble(model, sourceText, payload, PromptCatalog.INVOICE_REPAIR_SYSTEM_INSTRUCTIONS,
        maxInputCharacters);
  }

  private Map<String, Object> savedObject(Object value) {
    return value == null ? Map.of() : Json.object(Json.write(value));
  }

  private AiProvider.Request assemble(
      String model, String sourceText, Map<String, Object> context, String systemInstructions,
      int maxInputCharacters) {
    if (sourceText == null || sourceText.isBlank())
      throw DomainException.invalid("Não existe texto extraído da fatura para analisar; revê a extração OCR.");

    String instructions = prompts.prompt(systemInstructions);
    if (context.containsKey("alertTarget"))
      instructions += "\n" + prompts.prompt(PromptCatalog.REPAIR_RECORD_SCOPE_INSTRUCTIONS).stripTrailing();
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
