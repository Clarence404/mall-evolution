#!/usr/bin/env bash
# 阶段一遗留：只对应 stage-1-monolith 标签（那时只有 mall-app 一个进程、部署在云主机上），main 上不再维护；阶段三起改为 Kubernetes，见 deploy/k8s
# （调用它的流水线 .github/workflows/main.yml 已从 main 删除，只在 stage-1-monolith 标签里）
# 逐台发布：从负载均衡摘下 → 替换 jar → systemctl restart → 健康检查通过后挂回
# 健康检查走管理端口 18080（prod profile 的 management.server.port）
# 用法：deploy/deploy.sh mall-app/target/mall-app.jar host1 host2
set -euo pipefail

JAR="$1"; shift
HOSTS=("$@")

lb_detach() { echo "[lb] 摘下 $1（替换为云负载均衡的 CLI 调用）"; }
lb_attach() { echo "[lb] 挂回 $1（替换为云负载均衡的 CLI 调用）"; }

for host in "${HOSTS[@]}"; do
  lb_detach "$host"
  sleep 15                                    # 等在途请求处理完
  scp "$JAR" "deploy@${host}:/opt/mall-evolution/mall-app.jar.new"
  ssh "deploy@${host}" 'sudo mv /opt/mall-evolution/mall-app.jar.new /opt/mall-evolution/mall-app.jar && sudo systemctl restart mall-evolution'
  for i in $(seq 1 60); do
    if ssh "deploy@${host}" 'curl -fs localhost:18080/actuator/health/readiness' >/dev/null; then
      echo "[ok] ${host} 健康检查通过"
      break
    fi
    [[ $i -eq 60 ]] && { echo "[fail] ${host} 启动超时，停止发布"; exit 1; }
    sleep 2
  done
  lb_attach "$host"
done
