#!/usr/bin/env bash
# 大促预案里能脚本化的几步（其余如 RocketMQ 托管实例升规格、库存库与 Redis 升规格在云控制台执行）
#   T-3 天：bash deploy/promo/promo-checklist.sh scale-up
#   T-1 天：bash deploy/promo/promo-checklist.sh split 20001 20002 ...   （活动 SKU 分 10 桶）
#   大促后：bash deploy/promo/promo-checklist.sh merge 20001 20002 ... ；T+2 天 scale-down
set -euo pipefail
NS=mall
INVENTORY=${INVENTORY_URL:-http://inventory-service.mall.svc}

case "${1:-}" in
  scale-up)
    kubectl -n "$NS" patch hpa mall-app -p '{"spec":{"minReplicas":16}}'
    kubectl -n "$NS" patch hpa inventory-service -p '{"spec":{"minReplicas":8}}'
    ;;
  scale-down)
    kubectl -n "$NS" patch hpa mall-app -p '{"spec":{"minReplicas":4}}'
    kubectl -n "$NS" patch hpa inventory-service -p '{"spec":{"minReplicas":3}}'
    ;;
  split)
    shift
    for sku in "$@"; do curl -fsS -X POST "$INVENTORY/internal/inventory/buckets/$sku/split?count=10"; done
    ;;
  merge)
    shift
    for sku in "$@"; do curl -fsS -X POST "$INVENTORY/internal/inventory/buckets/$sku/merge"; done
    ;;
  *)
    echo "用法: $0 scale-up|scale-down|split <sku...>|merge <sku...>"; exit 1
    ;;
esac
