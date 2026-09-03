package io.github.rigazilla.memory.cognition.contradiction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Strategy used to resolve a detected memory contradiction.
 */
public enum ResolutionStrategy {

    /** Most-recently observed memory wins; the older one is superseded. */
    RECENCY("recency"),

    /** Highest-confidence memory wins; the lower-confidence one is superseded. */
    CONFIDENCE("confidence"),

    /** Both memories are valid in different contexts — neither is superseded. */
    COEXISTENCE("coexistence");

    private final String value;

    ResolutionStrategy(String value) {
        this.value = value;
    }

    /** JSON / serialised form used in LLM output and gRPC metadata. */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ResolutionStrategy fromValue(String value) {
        if (value == null) {
            return RECENCY;
        }
        for (ResolutionStrategy s : values()) {
            if (s.value.equalsIgnoreCase(value)) {
                return s;
            }
        }
        return RECENCY;
    }
}
