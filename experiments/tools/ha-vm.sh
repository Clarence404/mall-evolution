#!/bin/sh
# 跑在放中间件的那台 Linux 虚拟机上的小工具，由 run-batch.ps1 通过 ssh 传过去调用。用途是发现「环境自己卡了」：
#   reset        清空 MySQL 的语句统计
#   max          本轮 COMMIT 的最大耗时（毫秒）；远大于平时说明磁盘或虚拟机停顿过
#   status       行锁等待等几个全局计数（实验前后各取一次，相减得本轮的量）
#   probe-start  后台起一个探针：每 0.2 秒做一次 4KB 同步写并记录耗时，同时记录整机 CPU 忙碌与 iowait 占比
#   probe-stop   停探针
#   probe-cat    输出探针记录：epoch_s,fsync_ms,cpu_busy_pct,cpu_iowait_pct
# MySQL 容器名默认 mall-mysql，root 密码取容器自己的环境变量，这里不出现任何密码。
MYSQL_CONTAINER="${MYSQL_CONTAINER:-mall-mysql}"
DIR="${HA_VM_DIR:-/root}"
PID="$DIR/.ha-probe.pid"; OUT="$DIR/.ha-probe.csv"; BIN="$DIR/.ha-probe.bin"

q() { docker exec -i "$MYSQL_CONTAINER" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N 2>/dev/null'; }
up_ms() { awk '{printf "%d", $1 * 1000}' /proc/uptime; }
cpu() { awk '/^cpu /{print $2+$3+$4+$7+$8+$9, $6, $2+$3+$4+$5+$6+$7+$8+$9}' /proc/stat; }   # 忙碌、iowait、总

case "$1" in
  reset) echo "truncate table performance_schema.events_statements_summary_by_digest" | q ;;
  max) echo "select coalesce(round(max(max_timer_wait)/1e9),0) from performance_schema.events_statements_summary_by_digest where digest_text = 'COMMIT'" | q ;;
  status) echo "show global status where Variable_name like 'Innodb_row_lock%' or Variable_name in ('Com_commit','Com_rollback','Questions','Innodb_rows_inserted','Innodb_rows_updated','Innodb_data_fsyncs','Innodb_os_log_fsyncs','Threads_running','Max_used_connections')" | q ;;
  probe-start)
    [ -f "$PID" ] && kill "$(cat "$PID")" 2>/dev/null
    nohup sh "$0" probe-loop >/dev/null 2>&1 &
    echo $! > "$PID" ;;
  probe-loop)
    : > "$OUT"; dd if=/dev/zero of="$BIN" bs=4k count=1 conv=fsync 2>/dev/null
    set -- $(cpu); pb=$1; pw=$2; pt=$3; last=$(date +%s); busy=0; wait=0
    while :; do
      a=$(up_ms); dd if=/dev/zero of="$BIN" bs=4k count=1 conv=fsync,notrunc 2>/dev/null; d=$(( $(up_ms) - a ))
      now=$(date +%s)
      if [ "$now" != "$last" ]; then
        set -- $(cpu); dt=$(( $3 - pt )); [ "$dt" -gt 0 ] && { busy=$(( ($1 - pb) * 100 / dt )); wait=$(( ($2 - pw) * 100 / dt )); }
        pb=$1; pw=$2; pt=$3; last=$now
      fi
      echo "$now,$d,$busy,$wait" >> "$OUT"
      sleep 0.2
    done ;;
  probe-stop) [ -f "$PID" ] && kill "$(cat "$PID")" 2>/dev/null; rm -f "$PID" "$BIN" ;;
  probe-cat) cat "$OUT" 2>/dev/null ;;
  *) echo "usage: $0 reset|max|status|probe-start|probe-stop|probe-cat" >&2; exit 2 ;;
esac
