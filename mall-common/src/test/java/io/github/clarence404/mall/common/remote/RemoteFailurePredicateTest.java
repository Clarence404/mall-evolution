package io.github.clarence404.mall.common.remote;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.ResourceAccessException;

class RemoteFailurePredicateTest {

    RemoteFailurePredicate predicate = new RemoteFailurePredicate();

    @Test
    void timeoutsAndServerErrorsCountAsFailures() {
        assertThat(predicate.test(new ResourceAccessException("Read timed out")))
                .isTrue();
        assertThat(predicate.test(new RemoteBizException(HttpStatus.BAD_GATEWAY, ProblemDetail.forStatus(502))))
                .isTrue();
    }

    @Test
    void stockInsufficientIsANormalBusinessResult() {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setProperty("code", "STOCK_INSUFFICIENT");
        assertThat(predicate.test(new RemoteBizException(HttpStatus.CONFLICT, pd)))
                .isFalse();
        assertThat(predicate.test(new IllegalArgumentException("bug"))).isFalse();
    }
}
