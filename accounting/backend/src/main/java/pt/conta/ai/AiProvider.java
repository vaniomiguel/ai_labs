package pt.conta.ai;

import java.util.*;

/** Provider-neutral transport; domain code never depends on Ollama request or response types. */
public interface AiProvider {
  /** A complete document cannot fit; retrying the same request cannot repair the input. */
  class ContextExceeded extends RuntimeException {
    public ContextExceeded(String message) { super(message); }
  }
  record Model(String name, String digest, boolean vision, boolean local, boolean completion) {}

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
  String complete(Request request);
}
