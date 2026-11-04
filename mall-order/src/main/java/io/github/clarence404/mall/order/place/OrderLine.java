package io.github.clarence404.mall.order.place;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** skuId 在 JSON 里按字符串传（如 "20001"），反序列化为 Long。 */
public record OrderLine(
        @NotNull @Positive Long skuId,
        @NotNull @Min(1) @Max(99) Integer quantity) {}
