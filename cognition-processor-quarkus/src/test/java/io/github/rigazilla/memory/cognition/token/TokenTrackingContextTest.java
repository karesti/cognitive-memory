package io.github.rigazilla.memory.cognition.token;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TokenTrackingContext}. Pure JUnit 5 — no CDI container required.
 */
class TokenTrackingContextTest {

    @AfterEach
    void cleanUp() {
        TokenTrackingContext.clear();
    }

    @Test
    void callSetsContextDuringActionAndReturnsResult() {
        String result = TokenTrackingContext.call("proc", "res", () -> {
            TokenTrackingContext.Entry entry = TokenTrackingContext.current();
            return entry.processId() + "/" + entry.resourceName();
        });

        assertThat(result).isEqualTo("proc/res");
    }

    @Test
    void callClearsContextAfterSuccess() {
        TokenTrackingContext.call("proc", "res", () -> "ok");

        assertThat(TokenTrackingContext.current()).isNull();
    }

    @Test
    void callClearsContextWhenActionThrows() {
        assertThatThrownBy(() -> TokenTrackingContext.call("proc", "res", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(TokenTrackingContext.current()).isNull();
    }
}
