package io.aster.policy.rest;

import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.security.AdminHmacVerifier;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PolicyVersionSourceHashAdminResourceTest {
    private PolicyVersionSourceHashAdminResource resource;
    private AdminHmacVerifier hmac;
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        resource = new PolicyVersionSourceHashAdminResource();
        hmac = mock(AdminHmacVerifier.class);
        entityManager = mock(EntityManager.class);
        resource.hmac = hmac;
        resource.entityManager = entityManager;
    }

    @Test
    void missingSignatureIsRejected() {
        doThrow(new WebApplicationException(Response.status(403).build()))
            .when(hmac).verify(any(), eq("GET"), anyString(), isNull(), eq(0L), isNull());
        assertThatThrownBy(() -> resource.list(mock(HttpHeaders.class), "tenant", null, null))
            .isInstanceOf(WebApplicationException.class)
            .extracting(e -> ((WebApplicationException) e).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void authorizedRequestReturnsRowsAndUsesTenant() {
        @SuppressWarnings("unchecked") TypedQuery<PolicyVersion> query = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(PolicyVersion.class))).thenReturn(query);
        when(query.setParameter("tenant", "tenant-a")).thenReturn(query);
        when(query.setMaxResults(200)).thenReturn(query);
        PolicyVersion version = new PolicyVersion();
        version.id = 42L;
        version.moduleName = "finance";
        version.functionName = "evaluate";
        version.sourceHash = "abc";
        version.active = true;
        when(query.getResultList()).thenReturn(List.of(version));

        Response response = resource.list(mock(HttpHeaders.class), "tenant-a", null, null);

        verify(hmac).verify(any(), eq("GET"), eq("/api/v1/admin/policy-versions/source-hashes"), isNull(), eq(0L), isNull());
        verify(query).setParameter("tenant", "tenant-a");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat((List<?>) response.getEntity()).hasSize(1);
    }

    @Test
    void moreThanTwoHundredIdsIsRejected() {
        String ids = String.join(",", java.util.Collections.nCopies(201, "1"));
        assertThatThrownBy(() -> resource.list(mock(HttpHeaders.class), "tenant", ids, null))
            .isInstanceOf(WebApplicationException.class)
            .extracting(e -> ((WebApplicationException) e).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void beforeIdContinuesLatestTenantResults() {
        @SuppressWarnings("unchecked") TypedQuery<PolicyVersion> query = mock(TypedQuery.class);
        when(entityManager.createQuery(contains("v.id < :beforeId"), eq(PolicyVersion.class))).thenReturn(query);
        when(query.setParameter("tenant", "tenant-a")).thenReturn(query);
        when(query.setParameter("beforeId", 250L)).thenReturn(query);
        when(query.setMaxResults(200)).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());

        Response response = resource.list(mock(HttpHeaders.class), "tenant-a", null, 250L);

        verify(query).setParameter("beforeId", 250L);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
