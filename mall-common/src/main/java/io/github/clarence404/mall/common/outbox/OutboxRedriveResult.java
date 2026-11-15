package io.github.clarence404.mall.common.outbox;

/**
 * 重投的结果。
 *
 * @param redriven 这次调用实际放回待发送的条数；记录已经不是失败状态（比如别人刚重投过）时是 0，不算错误
 * @param remaining 调用之后还剩多少条失败记录（批量重投时按同样的 topic 条件数；单条重投时数全部）。
 *     大于 0 说明一次没处理完，可以再调一次
 */
public record OutboxRedriveResult(int redriven, long remaining) {}
