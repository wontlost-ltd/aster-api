package io.aster.policy.admin;

import io.aster.policy.security.AdminHmacVerifier;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** 迁移端点：内部 HMAC 验签、batchSize 校验与响应形状。迁移语义见 {@link ResolverMigrationServiceTest}。 */
class ResolverMigrationResourceTest {
    private ResolverMigrationResource resource;
    private AdminHmacVerifier hmac;
    private ResolverMigrationService service;

    @BeforeEach
    void setUp() {
        resource = new ResolverMigrationResource();
        hmac = mock(AdminHmacVerifier.class);
        service = mock(ResolverMigrationService.class);
        resource.hmac = hmac;
        resource.migration = service;
    }

    @Test
    void unsignedCallIsRejectedBeforeAnyWork() {
        doThrow(new WebApplicationException(Response.status(403).build()))
            .when(hmac).verify(any(), eq("POST"), eq(ResolverMigrationResource.PATH), isNull(), eq(0L), isNull());
        assertThatThrownBy(() -> resource.run(mock(HttpHeaders.class), null))
            .isInstanceOf(WebApplicationException.class)
            .extracting(e -> ((WebApplicationException) e).getResponse().getStatus()).isEqualTo(403);
        verifyNoInteractions(service);
    }

    @Test
    void defaultsBatchSizeToFiftyAndReturnsReport() {
        when(service.run(50, null)).thenReturn(new ResolverMigrationService.RunReport(3, 2, 1,
            List.of(new ResolverMigrationService.Failure(7L, "E801 boom"))));

        Response response = resource.run(mock(HttpHeaders.class), null);

        assertThat(response.getStatus()).isEqualTo(200);
        var report = (ResolverMigrationService.RunReport) response.getEntity();
        assertThat(report.processed()).isEqualTo(3);
        assertThat(report.succeeded()).isEqualTo(2);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.failures()).containsExactly(new ResolverMigrationService.Failure(7L, "E801 boom"));
    }

    @Test
    void rejectsOutOfRangeBatchSize() {
        for (Integer bad : new Integer[]{0, -1, ResolverMigrationResource.MAX_BATCH_SIZE + 1}) {
            assertThatThrownBy(() -> resource.run(mock(HttpHeaders.class), bad))
                .isInstanceOf(WebApplicationException.class)
                .extracting(e -> ((WebApplicationException) e).getResponse().getStatus()).isEqualTo(400);
        }
        verify(service, never()).run(anyInt(), any());
    }

    @Test
    void passesExplicitBatchSize() {
        when(service.run(10, null)).thenReturn(new ResolverMigrationService.RunReport(0, 0, 0, List.of()));
        resource.run(mock(HttpHeaders.class), 10);
        verify(service).run(10, null);
    }
}
