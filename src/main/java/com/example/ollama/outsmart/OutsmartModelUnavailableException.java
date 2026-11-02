package com.example.ollama.outsmart;

/** Signals that a local model could not be reached, so its game turn must not be committed. */
public class OutsmartModelUnavailableException extends IllegalStateException {

    /** Includes a concise reason suitable for the Outsmart API and spectator UI. */
    public OutsmartModelUnavailableException(String message) {
        super(message);
    }
}
