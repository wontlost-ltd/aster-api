package io.aster.policy.revalidation;

import io.aster.policy.security.AdminHmacVerifier;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;

class RevalidationAdminResourceTest {
    RevalidationAdminResource resource;
    AdminHmacVerifier hmac;
    RevalidationRunService runs;
    HttpHeaders headers;

    @BeforeEach void setUp() {
        resource = new RevalidationAdminResource();
        hmac = mock(AdminHmacVerifier.class);
        runs = mock(RevalidationRunService.class);
        headers = mock(HttpHeaders.class);
        resource.hmac = hmac;
        resource.runs = runs;
        resource.entityManager = mock(EntityManager.class);
    }

    @Test void validSignatureCreatesRunAndRoleHeaderDoesNotReplaceSignature() {
        when(runs.enqueue("all", null, "admin")).thenReturn(Optional.of(41L));
        Response response = resource.create(headers, "{\"scope\":\"all\"}");
        assertThat(response.getStatus()).isEqualTo(202);
        verify(hmac).verify(eq(headers), eq("POST"), eq("/api/v1/admin/revalidation/runs"),
            eq("application/json"), anyLong(), anyString());
    }

    @Test void requestedByIsRecordedAsAdminActor() {
        when(runs.enqueue("all", null, "admin:user-7")).thenReturn(Optional.of(42L));
        assertThat(resource.create(headers, "{\"scope\":\"all\",\"requestedBy\":\"user-7\"}").getStatus()).isEqualTo(202);
        verify(runs).enqueue("all", null, "admin:user-7");
    }

    @Test void malformedRequestedByIsRejected() {
        assertThatThrownBy(() -> resource.create(headers, "{\"scope\":\"all\",\"requestedBy\":\"u 7;drop\"}"))
            .isInstanceOf(WebApplicationException.class)
            .extracting(e -> ((WebApplicationException) e).getResponse().getStatus()).isEqualTo(400);
        verifyNoInteractions(runs);
    }

    @Test void activeRunReturnsConflict() {
        when(runs.enqueue("all", null, "admin")).thenReturn(Optional.empty());
        assertThat(resource.create(headers, "{\"scope\":\"all\"}").getStatus()).isEqualTo(409);
    }

    @Test void signedRunLookupReturnsStatus() {
        Query query = mock(Query.class);
        when(resource.entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter("id", 41L)).thenReturn(query);
        when(query.getResultList()).thenReturn(List.<Object>of((Object) new Object[]{41L, "tc", "all", null, "done",
            12L, 12L, 12, 12, 1, null, null, null, "admin:user-7"}));
        Response response = resource.get(headers, 41L);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(((java.util.Map<?, ?>) response.getEntity()).get("triggeredBy")).isEqualTo("admin:user-7");
        verify(hmac).verify(eq(headers), eq("GET"), eq("/api/v1/admin/revalidation/runs/41"), isNull(), eq(0L), isNull());
    }

    @Test void rejectedSignatureStopsRequestEvenWhenRoleHeaderExists() {
        when(headers.getHeaderString("X-User-Role")).thenReturn("ADMIN");
        doThrow(new WebApplicationException(Response.status(401).build()))
            .when(hmac).verify(eq(headers), eq("POST"), anyString(), anyString(), anyLong(), anyString());
        assertThatThrownBy(() -> resource.create(headers, "{\"scope\":\"all\"}"))
            .isInstanceOf(WebApplicationException.class)
            .extracting(e -> ((WebApplicationException) e).getResponse().getStatus()).isEqualTo(401);
        verifyNoInteractions(runs);
    }
}
