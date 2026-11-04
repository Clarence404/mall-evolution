package io.github.clarence404.mall.common.id;

/** 机器号来自 Redis 租约的生成器：每次发号前确认租约仍然有效，失效就拒绝，不冒撞号的风险。 */
final class LeasedIdGenerator implements IdGenerator {

    private final IdGenerator delegate;
    private final WorkerIdLease lease;

    LeasedIdGenerator(IdGenerator delegate, WorkerIdLease lease) {
        this.delegate = delegate;
        this.lease = lease;
    }

    @Override
    public long nextId() {
        if (!lease.isValid()) {
            throw new IllegalStateException("雪花机器号 " + lease.workerId() + " 的租约已失效（Redis 续约失败超时或被别的实例占用），拒绝发号");
        }
        return delegate.nextId();
    }
}
