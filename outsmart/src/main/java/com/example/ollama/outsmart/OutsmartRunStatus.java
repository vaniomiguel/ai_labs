package com.example.ollama.outsmart;

/** Reports whether Outsmart is idle, running in the background, complete, or stopped by an error. */
public record OutsmartRunStatus(State state, String error) {

    /** The four states shown by game polling and the Run Game control. */
    public enum State { IDLE, RUNNING, COMPLETE, FAILED }
}
