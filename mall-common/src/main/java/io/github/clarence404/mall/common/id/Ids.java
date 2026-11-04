package io.github.clarence404.mall.common.id;

/**
 * 领域对象的静态工厂（如 {@code Order.create}）取 ID 的入口，启动时由 {@link IdConfig} 注入真正的生成器。
 */
public final class Ids {

    private static volatile IdGenerator generator = new SnowflakeIdGenerator(0);

    private Ids() {}

    public static long next() {
        return generator.nextId();
    }

    static void use(IdGenerator idGenerator) {
        generator = idGenerator;
    }
}
