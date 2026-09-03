package io.github.rigazilla.memory.cognition.contradiction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * High-level category of a detected memory contradiction.
 */
public enum ContradictionType {

    /** User's preference for the same domain has changed (e.g. "prefers Python" vs "prefers Go"). */
    PREFERENCE_CHANGE("preference_change"),

    /** A stable identity fact has changed (e.g. "works at Acme" vs "works at Beta Inc"). */
    IDENTITY_CHANGE("identity_change"),

    /** A tool, environment, or setting has changed (e.g. "uses VSCode" vs "uses IntelliJ"). */
    STATE_CHANGE("state_change"),

    /** The same topic is described in mutually exclusive terms. */
    SEMANTIC_CONFLICT("semantic_conflict"),

    /** The memories are compatible, complementary, or unrelated — no contradiction. */
    NONE("none");

    private final String value;

    ContradictionType(String value) {
        this.value = value;
    }

    /** JSON / serialised form used in LLM output and gRPC metadata. */
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ContradictionType fromValue(String value) {
        if (value == null) {
            return NONE;
        }
        for (ContradictionType t : values()) {
            if (t.value.equalsIgnoreCase(value)) {
                return t;
            }
        }
        return NONE;
    }
}
