package com.example.ollama.brochure;

/** Signals an invalid website address supplied to the brochure endpoint. */
public class InvalidWebsiteUrlException extends IllegalArgumentException {

    public InvalidWebsiteUrlException(String message) {
        super(message);
    }
}
