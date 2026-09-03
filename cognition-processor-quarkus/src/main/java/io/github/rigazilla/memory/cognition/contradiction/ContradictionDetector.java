package io.github.rigazilla.memory.cognition.contradiction;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * LangChain4j AI Service that decides whether two memory contents contradict each other.
 *
 * <p>Uses the shared "memory" named model configured in {@code application.properties}.
 * The response is deserialized into {@link ContradictionDetectionResponse}.
 */
@RegisterAiService(
        chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class,
        modelName = "memory")
public interface ContradictionDetector {

    /**
     * Analyse two memories and decide whether they contradict each other.
     *
     * @param memoryType  shared memory type (e.g. {@code "preference"}, {@code "fact"})
     * @param contentA    content of the first memory
     * @param observedAtA observed_at timestamp of memory A (ISO-8601 or empty string)
     * @param contentB    content of the second memory
     * @param observedAtB observed_at timestamp of memory B (ISO-8601 or empty string)
     * @return structured contradiction analysis
     */
    @SystemMessage(fromResource = "prompts/contradiction-detector-system.md")
    @UserMessage("""
            Analyse the following two memories and determine whether they contradict each other.

            Memory type: {{memoryType}}

            Memory A (observed_at: {{observedAtA}}):
            {{contentA}}

            Memory B (observed_at: {{observedAtB}}):
            {{contentB}}

            Return a JSON object with:
            - contradicts: boolean — true if the memories express conflicting claims
            - contradictionType: one of "preference_change", "identity_change", "state_change", \
"semantic_conflict", "none"
            - recommendedStrategy: one of "recency", "confidence", "coexistence"
            - rationale: one sentence explaining the decision
            """)
    ContradictionDetectionResponse detect(
            @V("memoryType")  String memoryType,
            @V("contentA")    String contentA,
            @V("observedAtA") String observedAtA,
            @V("contentB")    String contentB,
            @V("observedAtB") String observedAtB
    );
}
