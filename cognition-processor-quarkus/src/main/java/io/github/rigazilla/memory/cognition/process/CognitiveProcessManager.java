package io.github.rigazilla.memory.cognition.process;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Orchestrates management operations across registered cognitive processes.
 */
@ApplicationScoped
public class CognitiveProcessManager {

    @Inject
    CognitiveProcessRegistry registry;

    public List<ManagedProcessInfo> listProcesses() {
        return registry.list().stream()
            .map(CognitiveProcess::inspect)
            .map(ManagedProcessInspection::toInfo)
            .toList();
    }

    public ManagedProcessInspection inspect(String processId) {
        return getProcess(processId).inspect();
    }

    public ManagedProcessInspection start(String processId, Map<String, List<String>> params) {
        CognitiveProcess process = getProcess(processId);
        process.start(params);
        return process.inspect();
    }

    public ManagedProcessInspection enable(String processId) {
        CognitiveProcess process = getProcess(processId);
        process.enable();
        return process.inspect();
    }

    public ManagedProcessInspection disable(String processId) {
        CognitiveProcess process = getProcess(processId);
        process.disable();
        return process.inspect();
    }

    public ManagedProcessInspection configure(String processId, Map<String, Object> config) {
        CognitiveProcess process = getProcess(processId);
        process.configure(config);
        return process.inspect();
    }

    private CognitiveProcess getProcess(String processId) {
        try {
            return registry.get(processId);
        } catch (NoSuchElementException e) {
            throw e;
        }
    }
}
