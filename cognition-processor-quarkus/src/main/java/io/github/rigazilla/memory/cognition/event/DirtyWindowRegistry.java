package io.github.rigazilla.memory.cognition.event;

import io.github.rigazilla.memory.cognition.config.CognitionConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Registry for managing dirty conversation windows.
 * Thread-safe registry that collects events into conversation-scoped debounce windows.
 * 
 * Synchronization strategy:
 * - ConcurrentHashMap for concurrent window updates
 * - ReentrantLock for promotion operations
 */
@ApplicationScoped
public class DirtyWindowRegistry {
    
    private static final Logger LOG = Logger.getLogger(DirtyWindowRegistry.class);
    
    // Active windows: conversationId -> DirtyWindow
    private final ConcurrentHashMap<String, DirtyWindow> windows = new ConcurrentHashMap<>();

    // Last promoted entry ID per conversation (for linking windows)
    private final ConcurrentHashMap<String, String> lastPromotedEntryId = new ConcurrentHashMap<>();

    // Lock for promotion operations
    private final ReentrantLock promotionLock = new ReentrantLock();
    
    @Inject
    CognitionConfig cognition;

    @Inject
    ScopeJobDispatcher jobDispatcher;
    
    /**
     * Accept an event into the registry.
     * Creates a new window or extends an existing one.
     * 
     * @param conversationId Conversation ID
     * @param eventCursor Event cursor
     * @param entryId Entry ID (may be null)
     * @param observedAt When the event was observed
     * @return true if a window was promoted due to max entries
     */
    public boolean acceptEvent(String conversationId, String eventCursor, String entryId, Instant observedAt) {
        int maxCheckpointWindows = cognition.scheduler().maxCheckpointWindows();
        // Check if we're at capacity
        while (windows.size() >= maxCheckpointWindows) {
            LOG.warnf("Checkpoint window limit reached (%d), promoting oldest due window", maxCheckpointWindows);
            DirtyWindow oldest = findOldestDueWindow();
            if (oldest != null) {
                promoteWindow(oldest, "checkpoint_bounded");
            } else {
                LOG.warnf("All windows are fresh, cannot make room for new event");
                break;
            }
        }
        
        // Create or extend window
        boolean[] promoted = {false};
        DirtyWindow[] windowToPromote = {null};
        windows.compute(conversationId, (k, existing) -> {
            if (existing == null) {
                // Get previous entry ID from last promoted window (null for first window)
                String previousEntryId = lastPromotedEntryId.get(conversationId);

                // Create new window
                DirtyWindow newWindow = new DirtyWindow(
                        conversationId, eventCursor, entryId,
                        observedAt, cognition.scheduler().debounceDelay(), previousEntryId);

                LOG.debugf("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                LOG.debugf("Window Created");
                LOG.debugf("  Conversation ID:    %s", conversationId);
                LOG.debugf("  First Cursor:       %s", eventCursor);
                LOG.debugf("  Entry ID:           %s", entryId != null ? entryId : "(none)");
                LOG.debugf("  Previous Entry ID:  %s",
                        previousEntryId != null ? previousEntryId : "(none - first window)");
                LOG.debugf("  Observed At:        %s", observedAt);
                LOG.debugf("  Due At:             %s", newWindow.getDueAt());
                LOG.debugf("  Debounce Delay:     %s", cognition.scheduler().debounceDelay());
                LOG.debugf("  Initial Event Count: 1");
                LOG.debugf("  Initial Entry Count: %d", entryId != null ? 1 : 0);
                LOG.debugf("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

                return newWindow;
            } else {
                // Extend existing window
                int oldEventCount = existing.getEventCount();
                int oldEntryCount = existing.getEntryCount();

                existing.extend(eventCursor, entryId, observedAt);

                LOG.debugf("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                LOG.debugf("Window Extended");
                LOG.debugf("  Conversation ID:    %s", conversationId);
                LOG.debugf("  Latest Cursor:      %s", eventCursor);
                LOG.debugf("  Entry ID:           %s", entryId != null ? entryId : "(none)");
                LOG.debugf("  Observed At:        %s", observedAt);
                LOG.debugf("  Event Count:        %d → %d", oldEventCount, existing.getEventCount());
                LOG.debugf("  Entry Count:        %d → %d", oldEntryCount, existing.getEntryCount());
                LOG.debugf("  Window Age:         %s", existing.getAge(observedAt));
                LOG.debugf("  Due At:             %s", existing.getDueAt());
                LOG.debugf("  Time Until Due:     %s", Duration.between(observedAt, existing.getDueAt()));
                LOG.debugf("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

                // Check if we should promote immediately due to max entries
                if (existing.isMaxEntriesReached(cognition.scheduler().maxBatchEntries())) {
                    LOG.infof("Window for conversation %s reached max entries (%d), promoting immediately",
                             conversationId, cognition.scheduler().maxBatchEntries());
                    promoted[0] = true;
                    windowToPromote[0] = existing;  // Save reference before removing
                    return null; // Remove from map, will be promoted
                }

                return existing;
            }
        });

        // If we removed the window due to max entries, promote it now
        if (promoted[0]) {
            promoteWindow(windowToPromote[0], "max_entries");
        }
        
        return promoted[0];
    }
    
    /**
     * Find windows that are ready for promotion.
     * Called by the promotion scheduler.
     * 
     * @param now Current time
     * @return List of windows to promote
     */
    public List<DirtyWindow> findReadyWindows(Instant now) {
        List<DirtyWindow> ready = new ArrayList<>();
        
        for (DirtyWindow window : windows.values()) {
            if (shouldPromote(window, now)) {
                ready.add(window);
            }
        }
        
        // Sort by dueAt (oldest first)
        ready.sort(Comparator.comparing(DirtyWindow::getDueAt));
        
        if (!ready.isEmpty()) {
            LOG.debugf("Found %d windows ready for promotion:", ready.size());
            for (DirtyWindow window : ready) {
                String trigger = determinePromotionTrigger(window, now);
                LOG.debugf("  - %s (trigger: %s, age: %s, entries: %d)", 
                          window.getConversationId(), trigger, window.getAge(now), window.getEntryCount());
            }
        }
        
        return ready;
    }
    
    /**
     * Check if a window should be promoted.
     */
    private boolean shouldPromote(DirtyWindow window, Instant now) {
        // Trigger 1: Debounce delay expired
        if (window.isDue(now)) {
            return true;
        }
        
        // Trigger 2: Max batch age reached
        if (window.isMaxAgeReached(now, cognition.scheduler().maxBatchAge())) {
            return true;
        }

        // Trigger 3: Max batch entries reached
        if (window.isMaxEntriesReached(cognition.scheduler().maxBatchEntries())) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Determine which trigger condition caused promotion.
     */
    private String determinePromotionTrigger(DirtyWindow window, Instant now) {
        if (window.isMaxEntriesReached(cognition.scheduler().maxBatchEntries())) {
            return "max_batch_entries";
        } else if (window.isMaxAgeReached(now, cognition.scheduler().maxBatchAge())) {
            return "max_batch_age";
        } else if (window.isDue(now)) {
            return "debounce_delay";
        } else {
            return "unknown";
        }
    }
    
    /**
     * Promote a list of windows to scope jobs.
     * Thread-safe operation using promotion lock.
     * 
     * @param windowsToPromote Windows to promote
     */
    public void promoteWindows(List<DirtyWindow> windowsToPromote) {
        promotionLock.lock();
        try {
            for (DirtyWindow window : windowsToPromote) {
                // Remove from registry
                DirtyWindow removed = windows.remove(window.getConversationId());
                if (removed != null) {
                    // Determine trigger type
                    Instant now = Instant.now();
                    String trigger = determinePromotionTrigger(removed, now);
                    
                    LOG.debugf("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    LOG.debugf("Window Promoted");
                    LOG.debugf("  Conversation ID:    %s", removed.getConversationId());
                    LOG.debugf("  Trigger:            %s", trigger);
                    LOG.debugf("  Event Cursors:      %s → %s",
                            removed.getFirstEventCursor(), removed.getLatestEventCursor());
                    LOG.debugf("  Event Count:        %d", removed.getEventCount());
                    LOG.debugf("  Entry Count:        %d", removed.getEntryCount());
                    LOG.debugf("  Entry IDs:          %s", removed.getEntryIds());
                    LOG.debugf("  Window Age:         %s", removed.getAge(now));
                    LOG.debugf("  First Observed:     %s", removed.getFirstObservedAt());
                    LOG.debugf("  Latest Observed:    %s", removed.getLatestObservedAt());
                    LOG.debugf("  Due At:             %s", removed.getDueAt());
                    LOG.debugf("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    
                    promoteWindow(removed, trigger);
                }
            }
        } finally {
            promotionLock.unlock();
        }
    }
    
    /**
     * Promote a single window to a scope job.
     */
    private void promoteWindow(DirtyWindow window, String trigger) {
        LOG.infof("Promoting window for conversation %s (trigger: %s): %s",
                 window.getConversationId(), trigger, window);

        // Update last promoted entry ID for next window
        List<String> entryIds = window.getEntryIds();
        if (!entryIds.isEmpty()) {
            // entryIds is ordered (LinkedHashSet) - last element is chronologically last
            String lastEntryId = entryIds.get(entryIds.size() - 1);
            lastPromotedEntryId.put(window.getConversationId(), lastEntryId);
        }

        // Create scope job
        ScopeJob job = new ScopeJob(
            window.getConversationId(),
            window.getFirstEventCursor(),
            window.getLatestEventCursor(),
            window.getEntryIds(),
            window.getPreviousEntryId(),
            window.getFirstObservedAt(),
            Instant.now(),
            trigger
        );

        // Dispatch job
        jobDispatcher.dispatch(job);
    }
    
    /**
     * Find the oldest due window (for checkpoint bounds enforcement).
     */
    private DirtyWindow findOldestDueWindow() {
        Instant now = Instant.now();
        return windows.values().stream()
            .filter(w -> w.isDue(now))
            .min(Comparator.comparing(DirtyWindow::getDueAt))
            .orElse(null);
    }
    
    /**
     * Restore windows from checkpoint.
     */
    public void restoreWindows(List<SerializedWindow> serializedWindows) {
        promotionLock.lock();
        try {
            LOG.infof("Restoring %d windows from checkpoint", serializedWindows.size());
            
            for (SerializedWindow sw : serializedWindows) {
                DirtyWindow window = new DirtyWindow(
                    sw.conversationId(),
                    sw.firstEventCursor(),
                    sw.latestEventCursor(),
                    sw.entryIds(),
                    sw.previousEntryId(),
                    sw.firstObservedAt(),
                    sw.latestObservedAt(),
                    sw.dueAt(),
                    sw.eventCount()
                );

                windows.put(sw.conversationId(), window);

                LOG.debugf("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                LOG.debugf("Window Restored");
                LOG.debugf("  Conversation ID:    %s", window.getConversationId());
                LOG.debugf("  Event Cursors:      %s → %s",
                        window.getFirstEventCursor(), window.getLatestEventCursor());
                LOG.debugf("  Event Count:        %d", window.getEventCount());
                LOG.debugf("  Entry Count:        %d", window.getEntryCount());
                LOG.debugf("  Entry IDs:          %s", window.getEntryIds());
                LOG.debugf("  Previous Entry ID:  %s",
                        window.getPreviousEntryId() != null
                                ? window.getPreviousEntryId() : "(none - first window)");
                LOG.debugf("  First Observed:     %s", window.getFirstObservedAt());
                LOG.debugf("  Latest Observed:    %s", window.getLatestObservedAt());
                LOG.debugf("  Due At:             %s", window.getDueAt());
                LOG.debugf("  Time Until Due:     %s", Duration.between(Instant.now(), window.getDueAt()));
                LOG.debugf("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            }
            
            LOG.infof("Successfully restored %d windows", serializedWindows.size());
        } finally {
            promotionLock.unlock();
        }
    }
    
    /**
     * Get all windows for checkpoint serialization.
     */
    public List<SerializedWindow> serializeWindows() {
        List<SerializedWindow> serialized = new ArrayList<>();
        
        for (DirtyWindow window : windows.values()) {
            serialized.add(new SerializedWindow(
                window.getConversationId(),
                window.getFirstEventCursor(),
                window.getLatestEventCursor(),
                window.getEntryIds(),
                window.getPreviousEntryId(),
                window.getFirstObservedAt(),
                window.getLatestObservedAt(),
                window.getDueAt(),
                window.getEventCount()
            ));
        }
        
        if (!serialized.isEmpty()) {
            LOG.debugf("Serializing %d windows for checkpoint:", serialized.size());
            for (SerializedWindow sw : serialized) {
                LOG.debugf("  - %s: %d events, %d entries, due in %s", 
                          sw.conversationId(), sw.eventCount(), sw.entryIds().size(),
                          Duration.between(Instant.now(), sw.dueAt()));
            }
        }
        
        return serialized;
    }
    
    /**
     * Get current window count.
     */
    public int getWindowCount() {
        return windows.size();
    }
    
    /**
     * Get the age of the oldest window.
     */
    public Duration getOldestWindowAge() {
        Instant now = Instant.now();
        return windows.values().stream()
            .map(w -> w.getAge(now))
            .max(Comparator.naturalOrder())
            .orElse(Duration.ZERO);
    }
    
    /**
     * Clear all windows.
     * Used for testing and when handling invalidate events (cursor beyond retention window).
     */
    public void clear() {
        promotionLock.lock();
        try {
            int count = windows.size();
            windows.clear();
            lastPromotedEntryId.clear();
            if (count > 0) {
                LOG.infof("Cleared %d dirty windows", count);
            }
        } finally {
            promotionLock.unlock();
        }
    }
}
