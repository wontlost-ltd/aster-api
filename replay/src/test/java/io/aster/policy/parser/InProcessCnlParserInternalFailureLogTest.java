package io.aster.policy.parser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 锁定降级边界：只有用户源码错误被降级，内部异常仍记 ERROR 与完整堆栈。 */
class InProcessCnlParserInternalFailureLogTest {

    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
    private final Logger logger = Logger.getLogger(InProcessCnlParser.class.getName());
    private Handler handler;

    @BeforeEach
    void attach() {
        handler = new Handler() {
            @Override public void publish(LogRecord r) { records.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    @AfterEach
    void detach() {
        logger.removeHandler(handler);
    }

    @Test
    void internalExceptionIsLoggedAtErrorWithStackTrace() {
        RuntimeException boom = new NullPointerException("boom");

        InProcessCnlParser.CnlParseException wrapped = InProcessCnlParser.internalFailure(boom);

        assertSame(boom, wrapped.getCause());
        List<LogRecord> errors;
        synchronized (records) {
            errors = records.stream().filter(r -> r.getLevel().intValue() >= Level.SEVERE.intValue()).toList();
        }
        assertEquals(1, errors.size());
        assertSame(boom, errors.get(0).getThrown(), "内部异常必须带完整堆栈");
    }

    @Test
    void missingAstModuleIsInternalFailureNotUserSourceError() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> InProcessCnlParser.requireModule(null));
        assertNotNull(e.getMessage());
        // 经 internalFailure 包装后才是 CnlParseException，且记 ERROR
        assertInstanceOf(InProcessCnlParser.CnlParseException.class, InProcessCnlParser.internalFailure(e));
    }
}
