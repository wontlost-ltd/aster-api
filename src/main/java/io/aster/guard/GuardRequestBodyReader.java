package io.aster.guard;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import io.aster.guard.receipt.ActionHasher;
import io.quarkus.hibernate.validator.runtime.jaxrs.ResteasyReactiveViolationException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * guard 请求体解析（ADR 0040 §12 D2）：同一份原始字节既得到类型化请求，也得到 {@code action} 子树的 {@code actionHash}。
 *
 * <p>为什么不用框架的消息体读取器：{@code actionHash} 必须作用于服务端收到的 JSON（显式 null、未知键原样保留），
 * 请求 record 再序列化会补出 {@code "type":null} 等键；重复键也只能在解析层拒绝。
 *
 * <p>与框架读取器保持<b>同一套</b>失败响应（{@code GuardInvalidRequestRegressionTest} 逐字节锁定）：
 * <ul>
 *   <li>空体 → 请求为 null（与框架相同，交给服务层报必填）；</li>
 *   <li>类型不符（{@link MismatchedInputException}）原样抛出，由 Quarkus 内置映射器给出带行列号的 400——
 *       所以类型化请求从原始字节再解析一次，而不是 {@code treeToValue}（树节点没有行列号）；</li>
 *   <li>非法 JSON 等其余解析错误 → {@code WebApplicationException(400)}，由全局映射器输出；</li>
 *   <li>Bean Validation 违规 → {@link ResteasyReactiveViolationException}，与 {@code @Valid} 失败同一映射器。</li>
 * </ul>
 * 在此之上新增两类 400 {@code invalid_action}：任一层重复键（{@code STRICT_DUPLICATE_DETECTION}），
 * 以及 {@code action} 内不满足 I-JSON 的数值（{@link ActionHasher}）。
 */
@ApplicationScoped
public class GuardRequestBodyReader {

  /** 解析结果；{@code actionHash} 在 {@code action} 缺失或不是对象时为 null（服务层随后按必填项拒绝）。 */
  public record Parsed<T>(T value, String actionHash) {}

  private final ObjectMapper mapper;
  private final ActionHasher hasher;
  private final Validator validator;

  @Inject
  public GuardRequestBodyReader(ObjectMapper mapper, ActionHasher hasher, Validator validator) {
    this.mapper = mapper;
    this.hasher = hasher;
    this.validator = validator;
  }

  /**
   * @throws GuardInvalidActionException 重复键，或 {@code action} 含非有限数 / 超出 ±(2^53−1) 的整数
   * @throws MismatchedInputException    类型不符；资源方法须原样抛出，交给 Quarkus 内置映射器
   * @throws IOException                 读取请求体失败
   */
  public <T> Parsed<T> read(InputStream body, Class<T> type) throws IOException {
    byte[] bytes = readAll(body);
    if (bytes.length == 0) {
      return new Parsed<>(null, null);
    }
    T value = readTyped(bytes, type);
    if (value == null) {
      return new Parsed<>(null, null);
    }
    JsonNode action = readStrictTree(bytes).get("action");
    String actionHash = action != null && action.isObject() ? hasher.hash(action) : null;
    validate(value);
    return new Parsed<>(value, actionHash);
  }

  /** 与 Quarkus {@code FullyFeaturedServerJacksonMessageBodyReader} 相同的异常分流。 */
  private <T> T readTyped(byte[] bytes, Class<T> type) throws IOException {
    try {
      return mapper.readerFor(type).readValue(bytes);
    } catch (MismatchedInputException e) {
      if (e.getMessage() != null && e.getMessage().startsWith("No content")) {
        return null;
      }
      throw e;
    } catch (InvalidDefinitionException e) {
      throw e;
    } catch (StreamReadException | DatabindException | StreamConstraintsException e) {
      throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
    }
  }

  /**
   * 严格模式（重复键检测）读树。只有重复键映射为 {@code invalid_action}；其余解析错误（类型化解析已成功时
   * 不应出现）按框架同口径的 400 处理。
   */
  private JsonNode readStrictTree(byte[] bytes) throws IOException {
    ObjectReader strict = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    try {
      return strict.readTree(bytes);
    } catch (JsonParseException e) {
      if (isDuplicateField(e)) {
        throw new GuardInvalidActionException("请求体含重复键: " + e.getOriginalMessage());
      }
      throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
    }
  }

  /** Jackson 的重复键错误没有专门的异常类型，只能按 {@code DupDetector} 的固定文案识别。 */
  private static boolean isDuplicateField(JsonParseException e) {
    String message = e.getOriginalMessage();
    return message != null && message.startsWith("Duplicate field '");
  }

  private <T> void validate(T value) {
    Set<ConstraintViolation<T>> violations = validator.validate(value);
    if (!violations.isEmpty()) {
      throw new ResteasyReactiveViolationException(violations);
    }
  }

  private static byte[] readAll(InputStream body) throws IOException {
    return body == null ? new byte[0] : body.readAllBytes();
  }
}
