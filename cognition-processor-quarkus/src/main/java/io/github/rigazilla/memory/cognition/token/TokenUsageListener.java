package io.github.rigazilla.memory.cognition.token;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * LangChain4j {@link ChatModelListener} that captures token usage from every LLM response
 * and forwards it to {@link TokenTracker}.
 *
 * <p>Quarkus LangChain4j automatically discovers {@code @ApplicationScoped} beans that
 * implement {@link ChatModelListener} and wires them into all registered chat models.
 * No additional configuration is required.
 *
 * <p>The listener reads the active resource context from {@link TokenTrackingContext}
 * (a {@link ThreadLocal} set by the caller before the LLM invocation) to associate
 * token counts with the correct process and resource.
 *
 * <p>If no context is active the token counts are silently dropped — this handles
 * LLM calls made outside the tracked pipeline (e.g. health checks or ad-hoc calls).
 */
@ApplicationScoped
public class TokenUsageListener implements ChatModelListener {

    private static final Logger LOG = Logger.getLogger(TokenUsageListener.class);

    @Inject
    TokenTracker tokenTracker;

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        // Nothing to do on request — context is already set by the caller via TokenTrackingContext.
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        TokenTrackingContext.Entry entry = TokenTrackingContext.current();
        if (entry == null) {
            return;
        }
        TokenUsage usage = responseContext.chatResponse().tokenUsage();
        ModelProvider provider = responseContext.modelProvider();
        // Record the request even when usage is null — the request still happened.
        // TokenTracker treats null token counts as zero, so averageTokensPerRequest
        // remains accurate and the request is counted.
        tokenTracker.record(entry.processId(), entry.resourceName(),
                provider != null ? provider.name() : null,
                responseContext.chatResponse().modelName(),
                usage != null ? usage.inputTokenCount() : null,
                usage != null ? usage.outputTokenCount() : null);
        if (usage == null) {
            LOG.debugf("No token usage in response for %s/%s — provider may not report tokens",
                    entry.processId(), entry.resourceName());
        } else {
            LOG.debugf("Recorded tokens for %s/%s: input=%s output=%s",
                    entry.processId(), entry.resourceName(),
                    usage.inputTokenCount(), usage.outputTokenCount());
        }
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        // Token usage is not available on error — nothing to record.
    }
}
