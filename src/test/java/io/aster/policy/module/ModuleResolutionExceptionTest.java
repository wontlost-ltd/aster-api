package io.aster.policy.module;

import io.aster.policy.module.ModuleResolutionException.Code;
import io.aster.policy.module.ModuleResolutionException.Reason;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** aster-api#452：同码多原因的结构化 reason；code 与 getMessage() 不变，旧构造器兼容。 */
class ModuleResolutionExceptionTest {

    @Test
    void 每个原因只属于一个code() {
        assertThat(Reason.VERSION_REQUIRED.code()).isEqualTo(Code.IMPORT_VERSION_REQUIRED);
        assertThat(Reason.EMPTY_PATH.code()).isEqualTo(Code.MODULE_VERSION_NOT_FOUND);
        assertThat(Reason.VERSION_NOT_FOUND.code()).isEqualTo(Code.MODULE_VERSION_NOT_FOUND);
        for (Reason r : List.of(Reason.TENANT_REQUIRED, Reason.NOT_VISIBLE, Reason.NOT_FOUND, Reason.ALIASES_UNSUPPORTED,
                Reason.PARSE_FAILED, Reason.RESOLVER_UNAVAILABLE)) {
            assertThat(r.code()).as(r.name()).isEqualTo(Code.MODULE_NOT_VISIBLE);
        }
        assertThat(Reason.IMPORT_CYCLE.code()).isEqualTo(Code.MODULE_CYCLE);
        assertThat(Reason.LINK_FAILED.code()).isEqualTo(Code.MODULE_CYCLE);
    }

    @Test
    void 新构造器_code由reason决定_message原样() {
        var e = new ModuleResolutionException(Reason.IMPORT_CYCLE, "Module import cycle detected: a@1 -> a@1",
            Map.of("cycle", "a@1 -> a@1"), List.of(), null);
        assertThat(e.code()).isEqualTo(Code.MODULE_CYCLE);
        assertThat(e.reason()).isEqualTo(Reason.IMPORT_CYCLE);
        assertThat(e.params()).containsEntry("cycle", "a@1 -> a@1");
        assertThat(e.getMessage()).isEqualTo("Module import cycle detected: a@1 -> a@1");
    }

    @Test
    void 旧构造器兼容_按code给默认原因() {
        var e = new ModuleResolutionException(Code.MODULE_VERSION_NOT_FOUND, "x", List.of("1"));
        assertThat(e.code()).isEqualTo(Code.MODULE_VERSION_NOT_FOUND);
        assertThat(e.reason()).isEqualTo(Reason.VERSION_NOT_FOUND);
        assertThat(e.candidates()).containsExactly("1");
        assertThat(e.params()).isEmpty();
        assertThat(new ModuleResolutionException(Code.MODULE_NOT_VISIBLE, "y").reason()).isEqualTo(Reason.NOT_VISIBLE);
    }

    @Test
    void 不可见类原因不得带参数_ADR0015() {
        assertThatThrownBy(() -> new ModuleResolutionException(Reason.NOT_VISIBLE, "Imported module is not visible",
            Map.of("module", "secret.Lib"), List.of(), null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
