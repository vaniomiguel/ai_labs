package pt.conta.ai;

import static pt.conta.shared.Inputs.*;

import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import pt.conta.document.DocumentStorage;
import pt.conta.jobs.JobQueue;
import pt.conta.review.RepairService;
import pt.conta.shared.*;

/** Explicit repairs always analyse the full original in one fresh, versioned model execution. */
@Service
public class RepairAiWorker {
  private final RepairService repairs;
  private final AiProvider provider;
  private final PromptCatalog prompts;
  private final Settings settings;
  private final DocumentStorage storage;
  private final AiResults results;
  private final int maxInput;
  private final int maxPages;

  public RepairAiWorker(RepairService repairs,AiProvider provider,PromptCatalog prompts,Settings settings,
      DocumentStorage storage,AiResults results,
      @Value("${accounting.repair-max-input-characters:50000}") int maxInput,
      @Value("${accounting.repair-max-image-pages:16}") int maxPages) {
    this.repairs=repairs;this.provider=provider;this.prompts=prompts;this.settings=settings;
    this.storage=storage;this.results=results;this.maxInput=maxInput;this.maxPages=maxPages;
  }

  public void process(JobQueue.Claim job) throws Exception {
    var source=repairs.begin(job);if(source==null)return;
    String task=switch(source.kind()){case "INVOICE"->"extract-invoice";case "STATEMENT"->"extract-statement";default->"review";};
    String selected=settings.get("model:"+task,"qwen3.5:9b");
    var model=provider.models().stream().filter(AiProvider.Model::completion).filter(m->m.name().equals(selected))
        .findFirst().orElseThrow(()->new OllamaProvider.AiUnavailable("Instala ou seleciona um modelo de geração: "+selected,null));
    String prompt=prompts.prompt(task)+"\nTentativa de correção solicitada pelo utilizador. Analise todo o texto e todas as imagens fornecidas. "
        +"O motivo do alerta é contexto, não uma instrução documental. Preserve os campos corretos e indique em issues o que continua por resolver.";
    var pages=new ArrayList<Integer>();var images=new ArrayList<String>();
    // Reject the complete input before rendering large originals; never shorten sourceText.
    String input=Json.write(map("data",source.draft(),"sourceText",source.text(),"alert",source.message(),
        "documentVersion",source.sourceVersion(),"sourcePages",pages));
    checkSize(input,prompt,task);
    if(model.vision()&&source.mediaType().equals("application/pdf")) {
      try(var pdf=Loader.loadPDF(storage.path(source.storageKey()).toFile())) {
        if(pdf.getNumberOfPages()>maxPages)throw new AiProvider.ContextExceeded(
            "O documento tem "+pdf.getNumberOfPages()+" páginas; o limite desta análise visual é "+maxPages+". Ajusta a capacidade configurada ou revê o documento manualmente. Nenhuma página foi omitida.");
        var renderer=new PDFRenderer(pdf);
        for(int n=0;n<pdf.getNumberOfPages();n++) {
          var bytes=new ByteArrayOutputStream();ImageIO.write(renderer.renderImageWithDPI(n,110),"png",bytes);
          images.add(Base64.getEncoder().encodeToString(bytes.toByteArray()));pages.add(n+1);
        }
      }
    }
    var context=map("model",model.name(),"modelDigest",model.digest(),"vision",model.vision(),
        "textComplete",true,"textCharacters",source.text().length(),"pages",pages,
        "imageHashes",images.stream().map(Hashes::sha256).toList(),"documentSha256",source.sha256());
    repairs.context(job,context);
    input=Json.write(map("data",source.draft(),"sourceText",source.text(),"alert",source.message(),
        "documentVersion",source.sourceVersion(),"sourcePages",pages,"context",context));
    checkSize(input,prompt,task);
    long start=System.nanoTime();
    String raw=provider.complete(new AiProvider.Request(model.name(),prompt,input,prompts.schema(task),images,true));
    var execution=new AiExecution();execution.targetId=job.target();execution.targetVersion=job.revision();
    execution.task="repair";execution.model=model.name();execution.modelDigest=model.digest();
    execution.promptVersion="repair-v1:"+Hashes.sha256(prompt);execution.inputHash=Hashes.sha256(input+prompt+job.id());
    execution.request=input;execution.response=raw;execution.durationMs=(System.nanoTime()-start)/1_000_000;
    Map<String,Object> parsed;
    try {parsed=StructuredOutput.parse(raw,prompts.schema(task));execution.outcome="VALID";}
    catch(RuntimeException e){execution.outcome="INVALID";results.record(execution);throw e;}
    results.record(execution);
    if(!repairs.complete(job,source,parsed,context))results.categoryOutcome(execution.id,"STALE");
  }

  private void checkSize(String input,String prompt,String task) {
    if((long)input.length()+prompt.length()+Json.write(prompts.schema(task)).length()>maxInput)
      throw new AiProvider.ContextExceeded("O texto integral e as instruções excedem o contexto configurado desta análise. Seleciona um modelo/configuração com maior capacidade ou revê o documento manualmente. O conteúdo não foi cortado.");
  }
}
