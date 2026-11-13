package io.aster.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * R30+ audit P2：项目里有 12+ 处 {@code new ObjectMapper()} 散落各处。
 * ObjectMapper 是线程安全但构造昂贵的对象，每个静态字段都会重复 build
 * 一套 BeanSerializer cache。把它们合并到这一个工具类里。
 *
 * <p>提供两种 mapper：
 * <ul>
 *   <li>{@link #DEFAULT}：vanilla Jackson，等价于 {@code new ObjectMapper()}。
 *       大多数 LLM / snapshot / workflow 序列化用这一个就够。</li>
 *   <li>{@link #LENIENT}：容忍未知字段。给从外部网络读 JSON 且 schema 可能演化的场景用（如配额快照）。</li>
 * </ul>
 *
 * <p><b>Core IR 不经这里读</b>：所有 Core IR 读方（读成模型或读成树）统一走 core
 * {@code aster.core.ir.CoreIrJson.read} / {@code readTree}（版本预检 + schema 校验 + 不变式，ADR 0055 §5），
 * 写持久化 / 哈希用的 IR 走 {@code CoreIrJson.serialize}。
 *
 * <p>新代码应当直接引用这两个 final 字段。老代码可以渐进式迁移；只要把
 * {@code private static final ObjectMapper MAPPER = new ObjectMapper()}
 * 改成 {@code import static io.aster.common.JacksonMappers.DEFAULT;}
 * 并使用 DEFAULT 即可。
 */
public final class JacksonMappers {

    private JacksonMappers() {}

    /** 默认 ObjectMapper —— vanilla Jackson，无任何 feature toggle。 */
    public static final ObjectMapper DEFAULT = new ObjectMapper();

    /** 宽松 mapper —— 不抛 UnknownProperty，给从网络/磁盘读的非 Core IR JSON 用。 */
    public static final ObjectMapper LENIENT = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    /**
     * 缩进输出 mapper —— 给 debug / compiler / policy serializer 用。
     * 与 DEFAULT 共享同一份 BeanSerializer cache（Jackson 内部按 class
     * 缓存，feature 开关只影响序列化器选择），不会重复 build 反射 metadata。
     */
    public static final ObjectMapper PRETTY = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);
}
