package io.github.rigazilla.memory.cognition.token;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TokenUsageListener}. Pure JUnit 5 — collaborators are wired by hand.
 */
class TokenUsageListenerTest {

    private TokenTracker tracker;
    private SimpleMeterRegistry registry;
    private TokenUsageListener listener;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        tracker = new TokenTracker();
        tracker.meterRegistry = registry;
        listener = new TokenUsageListener();
        listener.tokenTracker = tracker;
    }

    @AfterEach
    void cleanUp() {
        TokenTrackingContext.clear();
    }

    private ChatModelResponseContext responseWith(dev.langchain4j.model.output.TokenUsage usage) {
        ChatRequest request = ChatRequest.builder()
                .messages(dev.langchain4j.data.message.UserMessage.from("hi"))
                .build();
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("hello"))
                .modelName("llama3.2")
                .tokenUsage(usage)
                .build();
        return new ChatModelResponseContext(response, request, ModelProvider.OLLAMA, new HashMap<>());
    }

    @Test
    void recordsTokensWithProviderAndModelWhenContextIsActive() {
        TokenTrackingContext.set("proc", "extractor");

        listener.onResponse(responseWith(new dev.langchain4j.model.output.TokenUsage(100, 50)));

        assertThat(tracker.snapshot("proc", "extractor").totalTokens()).isEqualTo(150);
        assertThat(tracker.processSnapshot("proc").requestCount()).isEqualTo(1);
        assertThat(registry.get("llm.tokens")
                .tag("provider", "OLLAMA")
                .tag("model", "llama3.2")
                .counter().count()).isEqualTo(150);
    }

    @Test
    void dropsTokensWhenNoContextIsActive() {
        listener.onResponse(responseWith(new dev.langchain4j.model.output.TokenUsage(100, 50)));

        assertThat(tracker.processSnapshot("proc").requestCount()).isZero();
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void countsRequestEvenWhenTokenUsageIsNull() {
        TokenTrackingContext.set("proc", "extractor");

        listener.onResponse(responseWith(null));

        // The request must be counted even when the provider does not report token usage,
        // otherwise requestCount is deflated and averageTokensPerRequest is inflated.
        assertThat(tracker.snapshot("proc", "extractor").requestCount()).isEqualTo(1);
        assertThat(tracker.snapshot("proc", "extractor").totalTokens()).isZero();
    }
}
