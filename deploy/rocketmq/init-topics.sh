#!/bin/sh
# 建 Topic 与消费组。生产在托管实例控制台按同样的参数配置。
# 最大投递次数 = 首次 + 重试次数（-r）：points / shipment / order-timeout 16 次，notify 3 次；超过后进 %DLQ%{消费组}
set -e
ADMIN=/home/rocketmq/rocketmq-5.3.4/bin/mqadmin
N=${NAMESRV_ADDR:-rmq-namesrv:9876}

# 等 Broker 注册到 NameServer
for i in $(seq 1 30); do
  sh $ADMIN clusterList -n "$N" 2>/dev/null | grep -q broker-a && break
  sleep 2
done

sh $ADMIN updateTopic -n "$N" -c DefaultCluster -t order-paid -a +message.type=NORMAL
sh $ADMIN updateTopic -n "$N" -c DefaultCluster -t order-timeout -a +message.type=DELAY   # 定时消息
# 阶段三：跨服务一致性
sh $ADMIN updateTopic -n "$N" -c DefaultCluster -t payment-succeeded -a +message.type=NORMAL
sh $ADMIN updateTopic -n "$N" -c DefaultCluster -t order-cancelled -a +message.type=NORMAL
sh $ADMIN updateTopic -n "$N" -c DefaultCluster -t order-refund-required -a +message.type=NORMAL

sh $ADMIN updateSubGroup -n "$N" -c DefaultCluster -g points-consumer -r 15
sh $ADMIN updateSubGroup -n "$N" -c DefaultCluster -g notify-consumer -r 2
sh $ADMIN updateSubGroup -n "$N" -c DefaultCluster -g shipment-consumer -r 15
sh $ADMIN updateSubGroup -n "$N" -c DefaultCluster -g order-timeout-consumer -r 15
sh $ADMIN updateSubGroup -n "$N" -c DefaultCluster -g order-payment-consumer -r 15
sh $ADMIN updateSubGroup -n "$N" -c DefaultCluster -g inventory-confirm-consumer -r 15
sh $ADMIN updateSubGroup -n "$N" -c DefaultCluster -g inventory-release-consumer -r 15
sh $ADMIN updateSubGroup -n "$N" -c DefaultCluster -g payment-refund-consumer -r 15
echo "topics and consumer groups ready"
