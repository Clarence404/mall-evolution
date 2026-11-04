package io.github.clarence404.mall.user.points;

import io.github.clarence404.mall.common.mq.ConsumeRecordMapper;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 积分「不丢不重」：去重记录和加积分放在同一个本地事务里，插入影响 0 行说明处理过，直接返回。
 */
@Service
@RequiredArgsConstructor
public class PointsGrantService {

    private final ConsumeRecordMapper consumeRecordMapper;
    private final PointsMapper pointsMapper;

    @Transactional
    public void grant(OrderPaidEvent e) {
        if (consumeRecordMapper.insertIgnore("points-consumer", e.orderNo()) == 0) {
            return; // 重复投递
        }
        pointsMapper.add(
                e.userId(), e.payAmount().setScale(0, RoundingMode.DOWN).intValue(), e.orderNo());
    }
}
