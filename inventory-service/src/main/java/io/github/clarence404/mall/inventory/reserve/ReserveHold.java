package io.github.clarence404.mall.inventory.reserve;

/**
 * 预占扣完库存、事务还没提交时的回调点，正式环境没有实现（什么都不做）。
 *
 * <p>只有演示环境（demo profile）注册了实现，用来在持有库存行锁的这段时间里多停留几毫秒：高并发实验 E 靠它把
 * 「单行能扛多少单」的拐点拉到一台开发机压得到的范围内，再对比分桶。见 experiments/e-hot-stock。
 */
@FunctionalInterface
public interface ReserveHold {

    ReserveHold NONE = () -> {};

    /** 在预占事务内、所有库存行都已扣减之后调用；此时行锁还没释放。 */
    void afterDeduct();
}
