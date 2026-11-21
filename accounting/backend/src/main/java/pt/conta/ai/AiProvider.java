package pt.conta.ai;

import java.util.*;

/** Provider-neutral transport; domain code never depends on Ollama request or response types. */
public interface AiProvider {
  /** A complete document cannot fit; retrying the same request cannot repair the input. */
  class ContextExceeded extends RuntimeException {
    private final Usage usage;

    public ContextExceeded(String message) { this(message, null); }

    public ContextExceeded(String message, Usage usage) {
      super(message);
      this.usage = usage;
    }

    public Usage usage() { return usage; }
  }
  record Model(String name, String digest, boolean vision, boolean local, boolean completion) {}

  enum CapacitySource { CONFIGURED, RUNNING_MODEL }

  enum ContextWarning {
    NEAR_CONTEXT_LIMIT, CONTEXT_LIMIT_REACHED, CONTEXT_REJECTED, OUTPUT_LIMIT_REACHED, RESPONSE_INTERRUPTED
  }

  record Usage(Integer promptTokens, Integer responseTokens, int contextTokens,
      CapacitySource capacitySource, String doneReason, ContextWarning warning) {}

  record Completion(String content, Usage usage) {}

  record Request(
      String model,
      String instructions,
      String input,
      Map<String, Object> schema,
      List<String> images,
      boolean requireFullContext) {
    public Request(String model,String instructions,String input,Map<String,Object> schema,List<String> images) {
      this(model,instructions,input,schema,images,false);
    }
  }

  /** Lists installed local models and their capabilities. */
  List<Model> models();

  /** Produces a structured response for a bounded task; the caller validates its contents. */
  Completion complete(Request request);
}
