package io.github.clarence404.mall.common.mq;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

/**
 * 停机时把所有 PushConsumer 一起关，而不是等 Spring 销毁 Bean 时一个一个关。
 *
 * <p>PushConsumer.close() 要等手上的长轮询请求结束，实测一个约 30 秒。mall-app 有 4 个消费者，逐个关加上 Nacos 的 10 秒等待，
 * 从 SIGTERM 到进程退出要 75–90 秒（高可用实验 C），超过 Kubernetes 给的宽限期，后面的消费者会被强杀。
 * 并行关之后总耗时约等于最慢的那一个。之后 Bean 销毁时的 close() 再调一次是空操作。
 *
 * <p>阶段排在 Web 服务器之后：先停接请求、处理完在途请求（它们可能还要发消息），再关消费者。
 */
@Slf4j
public class ParallelConsumerShutdown implements SmartLifecycle {

    /** Web 服务器的优雅停机与停止分别在 MAX - 1024、MAX - 2048；数值小的后停。 */
    static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    private static final long TIMEOUT_SECONDS = 45;

    private final ObjectProvider<PushConsumer> consumers;
    private volatile boolean running;

    public ParallelConsumerShutdown(ObjectProvider<PushConsumer> consumers) {
        this.consumers = consumers;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        List<PushConsumer> all = consumers.stream().toList();
        if (all.isEmpty()) {
            return;
        }
        long begin = System.nanoTime();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CompletableFuture<?>[] closing = all.stream()
                    .map(consumer -> CompletableFuture.runAsync(() -> close(consumer), executor))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(closing).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("{} 个消费者已并行关闭，用时 {}ms", all.size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("并行关闭消费者超时或出错，剩下的交给 Bean 销毁", e); // 不挡住后面的停机步骤
        } finally {
            executor.shutdown(); // 不等：超时没关完的不能卡住停机
        }
    }

    private static void close(PushConsumer consumer) {
        try {
            consumer.close();
        } catch (Exception e) {
            log.warn("关闭消费者出错 {}", consumer.getConsumerGroup(), e);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
