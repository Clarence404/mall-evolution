package io.github.clarence404.mall.common.validation;

import jakarta.validation.groups.Default;

/** 校验分组：新建时。继承 Default，没写 groups 的格式约束同样生效。 */
public interface OnCreate extends Default {}
