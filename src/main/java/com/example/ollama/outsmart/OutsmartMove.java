package com.example.ollama.outsmart;

import java.util.Map;

/** One player's private strategy, coin choices, and messages for a single turn. */
public record OutsmartMove(
        String secretStrategy,
        String giveCoinTo,
        String takeCoinFrom,
        Map<String, String> privateMessages) {
}
