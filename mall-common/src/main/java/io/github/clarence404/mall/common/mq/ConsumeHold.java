package io.github.clarence404.mall.common.mq;

/**
 * 消费一条消息之前的回调点，正式环境没有实现（什么都不做）。
 *
 * <p>只有演示环境（demo profile）注册了实现，用来在处理每条消息之前多停留一会儿：高并发实验 G 靠它给消费者一个明确的处理能力
 * （每条 100ms、n 个线程约每秒 10n 条），再看突发流量在消息队列里怎么堆积、多久消化完。见 experiments/g-async-peak。
 */
@FunctionalInterface
public interface ConsumeHold {

    ConsumeHold NONE = consumerGroup -> {};

    /** 在消费线程上、业务处理之前调用。 */
    void beforeConsume(String consumerGroup);
}
