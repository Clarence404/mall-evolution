package io.github.clarence404.mall.order.place;

import io.github.clarence404.mall.common.validation.OnCreate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 收货地址：下单时所有字段必填（OnCreate），「发货前改地址」时只传要改的字段（OnPatch）。
 *
 * <p>两个分组都继承 Default：没写 groups 的格式约束（长度、手机号）在两个接口里都生效，{@code @NotBlank} 只在下单时生效。
 */
public record AddressForm(
        @NotBlank(groups = OnCreate.class) @Size(max = 20) String receiver,

        @NotBlank(groups = OnCreate.class) @Pattern(regexp = "1\\d{10}")
        String phone,

        @NotBlank(groups = OnCreate.class) @Size(max = 200) String detail) {}
