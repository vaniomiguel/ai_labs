import {
  Component,
  ElementRef,
  ViewChild,
  input,
  effect,
  inject,
  signal,
  OnDestroy,
} from "@angular/core";
import { Api } from "./api";
import { getDocument, GlobalWorkerOptions, PDFDocumentProxy } from "pdfjs-dist";

/** Local PDF.js viewer: source documents never leave the authenticated application. */
@Component({
  selector: "source-pdf",
  standalone: true,
  template: `<div class="pdf-toolbar">
      <button type="button" (click)="turn(-1)" [disabled]="page() <= 1">
        ←</button
      ><span>Página {{ page() }} de {{ count() }}</span
      ><button type="button" (click)="turn(1)" [disabled]="page() >= count()">
        →</button
      ><button
        type="button"
        (click)="zoomBy(-25)"
        [disabled]="zoom() <= 50"
        aria-label="Reduzir PDF"
      >
        −</button
      ><span>{{ zoom() }}%</span
      ><button
        type="button"
        (click)="zoomBy(25)"
        [disabled]="zoom() >= 200"
        aria-label="Ampliar PDF"
      >
        +</button
      ><a
        [href]="'/api/v1/documents/' + documentId() + '/original'"
        target="_blank"
        rel="noopener"
        >Abrir original ↗</a
      >
    </div>
    @if (error()) {
      <p class="notice">{{ error() }}</p>
    }
    <div class="pdf-paper">
      <canvas #canvas [style.width.%]="zoom()"></canvas>
    </div>`,
})
export class PdfViewer implements OnDestroy {
  readonly documentId = input.required<string>();
  readonly page = signal(1);
  readonly zoom = signal(100);
  readonly count = signal(0);
  readonly error = signal("");
  @ViewChild("canvas", { static: true }) canvas!: ElementRef<HTMLCanvasElement>;
  private api = inject(Api);
  private pdf?: PDFDocumentProxy;
  private loading = 0;
  private rendering: Promise<void> = Promise.resolve();
  constructor() {
    GlobalWorkerOptions.workerSrc = "/assets/pdf.worker.min.mjs";
    effect(() => {
      const id = this.documentId();
      void this.load(id);
    });
  }
  async load(id: string) {
    const sequence = ++this.loading;
    this.error.set("");
    try {
      const blob = await this.api.original(id);
      const pdf = await getDocument({
        data: await blob.arrayBuffer(),
        cMapUrl: "/assets/cmaps/",
        cMapPacked: true,
        standardFontDataUrl: "/assets/standard_fonts/",
        wasmUrl: "/assets/wasm/",
      }).promise;
      if (sequence !== this.loading) {
        await pdf.destroy();
        return;
      }
      await this.pdf?.destroy();
      this.pdf = pdf;
      this.count.set(pdf.numPages);
      this.page.set(1);
      await this.render();
    } catch {
      this.error.set("Não foi possível mostrar o PDF. Podes abrir o original.");
    }
  }
  async turn(delta: number) {
    this.page.update((n) => Math.max(1, Math.min(this.count(), n + delta)));
    await this.render();
  }
  async zoomBy(change: number) {
    this.zoom.update((value) => Math.max(50, Math.min(200, value + change)));
    await this.render();
  }
  private async render() {
    const pageNumber = this.page(),
      pdf = this.pdf;
    this.rendering = this.rendering
      .catch(() => {})
      .then(async () => {
        if (!pdf) return;
        const page = await pdf.getPage(pageNumber);
        const canvas = this.canvas.nativeElement;
        const viewport = page.getViewport({
          scale: (1.35 * this.zoom()) / 100,
        });
        canvas.width = viewport.width;
        canvas.height = viewport.height;
        await page.render({ canvas, viewport }).promise;
      });
    return this.rendering;
  }
  ngOnDestroy() {
    this.loading++;
    void this.pdf?.destroy();
  }
}
