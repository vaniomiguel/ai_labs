package com.example.ollama.brochure;

import java.io.IOException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exposes company brochure generation over HTTP. */
@RestController
@RequestMapping("/api/brochures")
public class BrochureController {

    private final BrochureService brochureService;

    public BrochureController(BrochureService brochureService) {
        this.brochureService = brochureService;
    }

    /** Returns a generated brochure as Markdown. */
    @GetMapping(produces = "text/markdown")
    public String generate(@RequestParam String url) throws IOException {
        return brochureService.generateBrochure(url);
    }
}
