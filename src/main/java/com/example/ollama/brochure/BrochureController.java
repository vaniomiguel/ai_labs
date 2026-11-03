package com.example.ollama.brochure;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Entry point for the future website brochure API. */
@RestController
@RequestMapping("/api/brochures")
public class BrochureController {

    private final BrochureService brochureService;

    public BrochureController(BrochureService brochureService) {
        this.brochureService = brochureService;
    }
}
