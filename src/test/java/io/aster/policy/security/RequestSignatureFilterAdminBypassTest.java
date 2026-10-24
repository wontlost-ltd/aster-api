package io.aster.policy.security;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestSignatureFilterAdminBypassTest {

    @Test
    void sourceHashAdminPathUsesItsResourceHmacInsteadOfGlobalSignature() {
        RequestSignatureFilter filter = new RequestSignatureFilter();
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/v1/admin/policy-versions/source-hashes");

        Uni<Void> result = filter.filter(context);

        assertThat(result).isNotNull();
        verify(context, never()).getEntityStream();
    }

    @Test
    void revalidationAdminPathsUseResourceHmacInsteadOfGlobalSignature() {
        RequestSignatureFilter filter = new RequestSignatureFilter();
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/v1/admin/revalidation/invalid");
        assertThat(filter.filter(context)).isNotNull();
        verify(context, never()).getEntityStream();
    }
}
