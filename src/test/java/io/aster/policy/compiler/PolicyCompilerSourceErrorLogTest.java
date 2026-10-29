package io.aster.policy.compiler;

import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.repository.PolicySourceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用户源码语法错误的日志口径：按版本执行路径每个版本只记一次 INFO（无堆栈），
 * 编辑器等无版本路径不产生 INFO/WARN/ERROR。
 */
class PolicyCompilerSourceErrorLogTest {

    private static final String BAD_SOURCE = "Module M.\nRule r given x as Int, produce Int:\n  Return ((.\n";

    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
    private final Logger root = Logger.getLogger("io.aster.policy");
    private Handler handler;
    private PolicySourceRepository repository;
    private PolicyCompiler compiler;

    @BeforeEach
    void setUp() {
        handler = new Handler() {
            @Override public void publish(LogRecord r) { records.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        handler.setLevel(Level.ALL);
        root.addHandler(handler);
        repository = mock(PolicySourceRepository.class);
        compiler = new PolicyCompiler(repository, new io.aster.policy.stability.StabilityEnforcement(),
            new io.aster.policy.analysis.RuleConflictDiagnostics());
    }

    @AfterEach
    void tearDown() {
        root.removeHandler(handler);
    }

    private void stubVersion(long id) {
        PolicyVersion version = new PolicyVersion();
        version.id = id;
        version.content = BAD_SOURCE;
        version.locale = "en-US";
        when(repository.findCoreJsonArtifact(id)).thenReturn(Optional.empty());
        when(repository.findVersionById(id)).thenReturn(Optional.of(version));
    }

    /** 源码错误日志：编译链路上其他 INFO（如「动态编译 CNL 源代码」）不计入。 */
    private List<LogRecord> sourceErrorLogs() {
        synchronized (records) {
            return records.stream()
                .filter(r -> r.getLevel().intValue() >= Level.INFO.intValue())
                .filter(r -> r.getMessage() != null && r.getMessage().contains("编译未通过"))
                .toList();
        }
    }

    private List<LogRecord> atLeast(Level level) {
        synchronized (records) {
            return records.stream().filter(r -> r.getLevel().intValue() >= level.intValue()).toList();
        }
    }

    @Test
    void sameVersionLogsSyntaxErrorOnceAtInfoWithoutStackTrace() {
        stubVersion(501L);

        assertThat(compiler.compile(501L).isSuccess()).isFalse();
        assertThat(compiler.compile(501L).isSuccess()).isFalse();

        List<LogRecord> logged = sourceErrorLogs();
        assertThat(logged).hasSize(1);
        assertThat(logged.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(logged.get(0).getThrown()).isNull();
        assertThat(atLeast(Level.WARNING)).isEmpty();
    }

    @Test
    void differentVersionsEachLogOnce() {
        stubVersion(502L);
        stubVersion(503L);

        compiler.compile(502L);
        compiler.compile(503L);
        compiler.compile(502L);

        assertThat(sourceErrorLogs()).hasSize(2);
        assertThat(atLeast(Level.WARNING)).isEmpty();
    }

    @Test
    void editorPathDoesNotLogAboveDebug() {
        var result = compiler.compile(BAD_SOURCE, "en-US");

        assertThat(result.isSuccess()).isFalse();
        assertThat(sourceErrorLogs()).isEmpty();
        assertThat(atLeast(Level.WARNING)).isEmpty();
    }
}
