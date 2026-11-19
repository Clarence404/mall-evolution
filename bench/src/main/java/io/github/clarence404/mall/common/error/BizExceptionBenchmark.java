package io.github.clarence404.mall.common.error;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * 业务异常的成本：在 depth 层调用之下抛出一个 {@link BizException}、在最外层接住，和两种对照——同样抛异常但不采集调用栈、
 * 不抛异常而是返回错误码。
 *
 * <p>异常贵在创建时采集调用栈（fillInStackTrace 要把当前线程的栈走一遍），栈越深越贵。一次 HTTP 请求从 Tomcat 进到业务代码，
 * 中间隔着过滤器链、Spring MVC、AOP 代理，通常有一百多层；depth 取 1、30、120 三档，120 接近线上的真实深度。
 * 熔断或舱壁拒绝下单时走的正是这条路径：{@code throw new BizException(CommonErrorCode.SYSTEM_BUSY)}，过载时每秒几千次。
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class BizExceptionBenchmark {

    @Param({"1", "30", "120"})
    public int depth;

    /** 现在的写法：抛 BizException，带完整调用栈。 */
    @Benchmark
    public Object throwWithStackTrace() {
        try {
            return descend(depth, 0);
        } catch (BizException e) {
            return e.getErrorCode();
        }
    }

    /** 对照一：同样抛异常，但异常类关掉了调用栈采集。 */
    @Benchmark
    public Object throwWithoutStackTrace() {
        try {
            return descend(depth, 1);
        } catch (BizException e) {
            return e.getErrorCode();
        }
    }

    /** 对照二：不抛异常，逐层返回错误码。 */
    @Benchmark
    public Object returnErrorCode() {
        return descend(depth, 2);
    }

    private Object descend(int remaining, int mode) {
        if (remaining > 1) {
            return descend(remaining - 1, mode);
        }
        return switch (mode) {
            case 0 -> throw new BizException(CommonErrorCode.SYSTEM_BUSY);
            case 1 -> throw new StacklessBizException(CommonErrorCode.SYSTEM_BUSY);
            default -> CommonErrorCode.SYSTEM_BUSY;
        };
    }

    /** 不采集调用栈的业务异常：只用来对照，正式代码里没有这个类。 */
    static final class StacklessBizException extends BizException {

        StacklessBizException(ErrorCode errorCode) {
            super(errorCode);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
