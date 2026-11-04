package io.github.clarence404.mall.common.error;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码。各模块在自己的 api 包里用枚举实现它，业务码 = 模块前缀 + 原因，对外发布后不再改含义。
 */
public interface ErrorCode {

    HttpStatus status();

    String title();

    String code();
}
