package com.example.ollama.brochure;

import java.util.List;

/** The model's structured selection of brochure-relevant website pages. */
public record BrochureLinks(List<BrochureLink> links) {
}
