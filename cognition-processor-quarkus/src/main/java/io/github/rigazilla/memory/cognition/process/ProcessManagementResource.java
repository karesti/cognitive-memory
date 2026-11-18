package io.github.rigazilla.memory.cognition.process;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/processes")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Process Management", description = "Control and inspect cognitive processes")
public class ProcessManagementResource {

    @Inject
    CognitiveProcessManager manager;

    @GET
    @Operation(summary = "List all processes",
        description = "Returns all registered cognitive processes and their current state.")
    public List<ManagedProcessInfo> list() {
        return manager.listProcesses();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Inspect a process",
        description = "Returns detailed information about a specific process, including runtime statistics.")
    @APIResponse(responseCode = "200", description = "Process details")
    @APIResponse(responseCode = "404", description = "Process not found")
    public ManagedProcessInspection inspect(
            @Parameter(description = "Process identifier", example = "durable-memory-extraction")
            @PathParam("id") String processId) {
        try {
            return manager.inspect(processId);
        } catch (NoSuchElementException e) {
            throw new WebApplicationException(e.getMessage(), 404);
        }
    }

    @POST
    @Path("/{id}/start")
    @Operation(summary = "Start a process",
        description = "Triggers process startup if the process is not already running. "
            + "An optional JSON body may be supplied to scope the run; "
            + "omitting the body preserves existing behaviour (all namespaces).")
    @APIResponse(responseCode = "200", description = "Process started")
    @APIResponse(responseCode = "404", description = "Process not found")
    @APIResponse(responseCode = "501", description = "Operation not supported for this process")
    public ManagedProcessInspection start(
            @Parameter(description = "Process identifier") @PathParam("id") String processId,
            @RequestBody(description = "Optional start parameters", required = false)
            ProcessStartRequest body) {
        Map<String, List<String>> params = body != null && body.namespacePrefix() != null
                ? Map.of("namespacePrefix", body.namespacePrefix())
                : Map.of();
        try {
            return manager.start(processId, params);
        } catch (NoSuchElementException e) {
            throw new WebApplicationException(e.getMessage(), 404);
        } catch (UnsupportedOperationException e) {
            throw new WebApplicationException(e.getMessage(), 501);
        }
    }

    @POST
    @Path("/{id}/enable")
    @Operation(summary = "Enable a process",
        description = "Enables a previously disabled cognitive process.")
    @APIResponse(responseCode = "200", description = "Process enabled")
    @APIResponse(responseCode = "404", description = "Process not found")
    @APIResponse(responseCode = "501", description = "Operation not supported for this process")
    public ManagedProcessInspection enable(
            @Parameter(description = "Process identifier") @PathParam("id") String processId) {
        try {
            return manager.enable(processId);
        } catch (NoSuchElementException e) {
            throw new WebApplicationException(e.getMessage(), 404);
        } catch (UnsupportedOperationException e) {
            throw new WebApplicationException(e.getMessage(), 501);
        }
    }

    @POST
    @Path("/{id}/disable")
    @Operation(summary = "Disable a process",
        description = "Disables a cognitive process, preventing it from processing events.")
    @APIResponse(responseCode = "200", description = "Process disabled")
    @APIResponse(responseCode = "404", description = "Process not found")
    @APIResponse(responseCode = "501", description = "Operation not supported for this process")
    public ManagedProcessInspection disable(
            @Parameter(description = "Process identifier") @PathParam("id") String processId) {
        try {
            return manager.disable(processId);
        } catch (NoSuchElementException e) {
            throw new WebApplicationException(e.getMessage(), 404);
        } catch (UnsupportedOperationException e) {
            throw new WebApplicationException(e.getMessage(), 501);
        }
    }

    @POST
    @Path("/{id}/config")
    @Operation(summary = "Configure a process",
        description = "Updates runtime configuration for a cognitive process. "
            + "Each process interprets the configuration map according to its own schema. "
            + "Unrecognised keys are silently ignored; recognised keys with invalid values are rejected.")
    @APIResponse(responseCode = "200", description = "Process configured")
    @APIResponse(responseCode = "400", description = "Invalid configuration value")
    @APIResponse(responseCode = "404", description = "Process not found")
    @APIResponse(responseCode = "501", description = "Operation not supported for this process")
    public ManagedProcessInspection configure(
            @Parameter(description = "Process identifier") @PathParam("id") String processId,
            @RequestBody(description = "Configuration parameters", required = true)
            ProcessConfigRequest body) {
        try {
            return manager.configure(processId, body.config());
        } catch (NoSuchElementException e) {
            throw new WebApplicationException(e.getMessage(), 404);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), 400);
        } catch (UnsupportedOperationException e) {
            throw new WebApplicationException(e.getMessage(), 501);
        }
    }
}
