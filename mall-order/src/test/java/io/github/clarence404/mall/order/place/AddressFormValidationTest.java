package io.github.clarence404.mall.order.place;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.mall.common.validation.OnCreate;
import io.github.clarence404.mall.common.validation.OnPatch;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 分组继承 Default：格式约束两边都生效，@NotBlank 只在下单时生效。 */
class AddressFormValidationTest {

    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void onCreateRequiresAllFields() {
        assertThat(validator.validate(new AddressForm(null, "13800000000", "杭州"), OnCreate.class))
                .hasSize(1);
    }

    @Test
    void onPatchAllowsMissingFieldsButKeepsFormatRules() {
        assertThat(validator.validate(new AddressForm(null, null, "杭州"), OnPatch.class))
                .isEmpty();
        assertThat(validator.validate(new AddressForm(null, "12345", null), OnPatch.class))
                .hasSize(1);
    }

    @Test
    void groupsCascadeThroughValidIntoNestedAddress() {
        PlaceOrderRequest req = new PlaceOrderRequest(
                List.of(new OrderLine(20001L, 1)), new AddressForm(null, "13800000000", "杭州"), null);
        assertThat(validator.validate(req, OnCreate.class)).hasSize(1);
    }
}
