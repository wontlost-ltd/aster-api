package io.aster.guard.receipt;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.time.Clock;

/**
 * guard 新服务的时间来源：生产注入 {@link Clock#systemUTC()}。
 *
 * <p>收据的签发时刻、过期判定与密钥 {@code notAfter} 检查都经由注入的 {@link Clock}，测试可用
 * {@code Clock.fixed} 或可变时钟替换（{@link DefaultBean}：任何其他 {@link Clock} bean 都会覆盖本 producer）。
 */
@ApplicationScoped
public class GuardClockProducer {

  @Produces
  @ApplicationScoped
  @DefaultBean
  Clock clock() {
    return Clock.systemUTC();
  }
}
