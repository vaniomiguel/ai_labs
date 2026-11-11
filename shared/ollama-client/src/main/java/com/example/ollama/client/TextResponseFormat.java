package com.example.ollama.client;

/** Output formats supported by text generation. */
public enum TextResponseFormat {
    /** Let the model provider use its default text format. */
    DEFAULT,
    /** Request a JSON object without imposing a particular schema. */
    JSON_OBJECT
}
