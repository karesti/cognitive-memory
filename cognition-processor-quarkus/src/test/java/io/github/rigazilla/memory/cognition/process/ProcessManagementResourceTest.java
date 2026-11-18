package io.github.rigazilla.memory.cognition.process;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ProcessManagementResource}.
 */
@QuarkusTest
class ProcessManagementResourceTest {

    @InjectMock
    CognitiveProcessManager manager;

    private ManagedProcessInspection mockInspection;

    @BeforeEach
    void setUp() {
        mockInspection = new ManagedProcessInspection(
            "test-process",
            "Test Process",
            "A test process",
            ManagedProcessState.ENABLED,
            Map.of("status", "running")
        );
    }

    @Test
    void testListProcesses() {
        List<ManagedProcessInfo> processes = List.of(
            new ManagedProcessInfo("process-1", "Process 1", "First process", ManagedProcessState.ENABLED),
            new ManagedProcessInfo("process-2", "Process 2", "Second process", ManagedProcessState.DISABLED)
        );
        when(manager.listProcesses()).thenReturn(processes);

        given()
            .when().get("/api/processes")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .contentType(ContentType.JSON)
            .body("size()", equalTo(2))
            .body("[0].id", equalTo("process-1"))
            .body("[1].id", equalTo("process-2"));

        verify(manager).listProcesses();
    }

    @Test
    void testInspectProcess() {
        when(manager.inspect("test-process")).thenReturn(mockInspection);

        given()
            .when().get("/api/processes/test-process")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .contentType(ContentType.JSON)
            .body("id", equalTo("test-process"))
            .body("displayName", equalTo("Test Process"))
            .body("state", equalTo("ENABLED"));

        verify(manager).inspect("test-process");
    }

    @Test
    void testInspectProcess_NotFound() {
        when(manager.inspect("unknown")).thenThrow(new NoSuchElementException("Process not found"));

        given()
            .when().get("/api/processes/unknown")
            .then()
            .statusCode(Response.Status.NOT_FOUND.getStatusCode());

        verify(manager).inspect("unknown");
    }

    @Test
    void testStartProcess() {
        when(manager.start(eq("test-process"), any())).thenReturn(mockInspection);

        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .when().post("/api/processes/test-process/start")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .contentType(ContentType.JSON)
            .body("id", equalTo("test-process"));

        verify(manager).start(eq("test-process"), any());
    }

    @Test
    void testStartProcess_WithNamespacePrefix() {
        when(manager.start(eq("test-process"), any())).thenReturn(mockInspection);

        given()
            .contentType(ContentType.JSON)
            .body("{\"namespacePrefix\": [\"user\", \"alice\"]}")
            .when().post("/api/processes/test-process/start")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .contentType(ContentType.JSON)
            .body("id", equalTo("test-process"));

        verify(manager).start(eq("test-process"), any());
    }

    @Test
    void testStartProcess_NotSupported() {
        when(manager.start(eq("test-process"), any()))
            .thenThrow(new UnsupportedOperationException("Start not supported"));

        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .when().post("/api/processes/test-process/start")
            .then()
            .statusCode(Response.Status.NOT_IMPLEMENTED.getStatusCode());

        verify(manager).start(eq("test-process"), any());
    }

    @Test
    void testEnableProcess() {
        when(manager.enable("test-process")).thenReturn(mockInspection);

        given()
            .when().post("/api/processes/test-process/enable")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .contentType(ContentType.JSON)
            .body("id", equalTo("test-process"))
            .body("state", equalTo("ENABLED"));

        verify(manager).enable("test-process");
    }

    @Test
    void testDisableProcess() {
        when(manager.disable("test-process")).thenReturn(mockInspection);

        given()
            .when().post("/api/processes/test-process/disable")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .contentType(ContentType.JSON)
            .body("id", equalTo("test-process"));

        verify(manager).disable("test-process");
    }

    @Test
    void testConfigureProcess() {
        when(manager.configure(eq("test-process"), any())).thenReturn(mockInspection);

        given()
            .contentType(ContentType.JSON)
            .body("{\"config\": {\"maxRetries\": 5, \"enabled\": true}}")
            .when().post("/api/processes/test-process/config")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .contentType(ContentType.JSON)
            .body("id", equalTo("test-process"));

        verify(manager).configure(eq("test-process"), any());
    }

    @Test
    void testConfigureProcess_EmptyConfig() {
        when(manager.configure(eq("test-process"), any())).thenReturn(mockInspection);

        given()
            .contentType(ContentType.JSON)
            .body("{\"config\": {}}")
            .when().post("/api/processes/test-process/config")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .contentType(ContentType.JSON)
            .body("id", equalTo("test-process"));

        verify(manager).configure(eq("test-process"), eq(Map.of()));
    }

    @Test
    void testConfigureProcess_NotFound() {
        when(manager.configure(eq("unknown"), any()))
            .thenThrow(new NoSuchElementException("Process not found"));

        given()
            .contentType(ContentType.JSON)
            .body("{\"config\": {\"key\": \"value\"}}")
            .when().post("/api/processes/unknown/config")
            .then()
            .statusCode(Response.Status.NOT_FOUND.getStatusCode());

        verify(manager).configure(eq("unknown"), any());
    }

    @Test
    void testConfigureProcess_InvalidValue() {
        when(manager.configure(eq("test-process"), any()))
            .thenThrow(new IllegalArgumentException("Invalid value for 'verifierEnabled'"));

        given()
            .contentType(ContentType.JSON)
            .body("{\"config\": {\"verifierEnabled\": \"false\"}}")
            .when().post("/api/processes/test-process/config")
            .then()
            .statusCode(Response.Status.BAD_REQUEST.getStatusCode());

        verify(manager).configure(eq("test-process"), any());
    }

    @Test
    void testConfigureProcess_NotSupported() {
        when(manager.configure(eq("test-process"), any()))
            .thenThrow(new UnsupportedOperationException("Configure not supported"));

        given()
            .contentType(ContentType.JSON)
            .body("{\"config\": {\"key\": \"value\"}}")
            .when().post("/api/processes/test-process/config")
            .then()
            .statusCode(Response.Status.NOT_IMPLEMENTED.getStatusCode());

        verify(manager).configure(eq("test-process"), any());
    }

    @Test
    void testConfigureProcess_ComplexConfig() {
        Map<String, Object> complexConfig = Map.of(
            "batchSize", 100,
            "retryDelayMs", 1000,
            "enabled", true,
            "tags", List.of("production", "high-priority")
        );

        when(manager.configure(eq("test-process"), eq(complexConfig))).thenReturn(mockInspection);

        given()
            .contentType(ContentType.JSON)
            .body("{\"config\": {\"batchSize\": 100, \"retryDelayMs\": 1000, "
                + "\"enabled\": true, \"tags\": [\"production\", \"high-priority\"]}}")
            .when().post("/api/processes/test-process/config")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .contentType(ContentType.JSON)
            .body("id", equalTo("test-process"));

        verify(manager).configure(eq("test-process"), eq(complexConfig));
    }
}
