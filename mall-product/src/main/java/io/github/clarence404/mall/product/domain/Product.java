package io.github.clarence404.mall.product.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Product {

    public static final int ON_SHELF = 1;

    private long id;
    private String name;
    private String image;
    private BigDecimal price;
    private int status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
