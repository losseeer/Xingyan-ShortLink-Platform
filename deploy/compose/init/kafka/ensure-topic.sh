#!/bin/sh
# 保证 topic 存在且分区数不少于期望值（M2-22）。
#
# 为什么不能简单串两条 kafka-topics：`--alter --partitions N` 在"已经有 N 个分区"时
# 会以 InvalidPartitionsException **退出 1**。这个容器是 consumer 的
# `service_completed_successfully` 前置条件，它一失败，consumer 就永远起不来
# （本机就因此把三档并行度的测量全跑成了空数——见 ITER-M2 §4）。
# 所以这里：只有实测分区数少于期望值才 alter，其余情况一律 0 退出；真失败才非 0。
set -u

K=/opt/kafka/bin/kafka-topics.sh
B="--bootstrap-server ${KAFKA_BOOTSTRAP:-kafka:9092}"
TOPIC="${TOPIC:-shortlink-click}"
PARTITIONS="${PARTITIONS:-4}"
REPLICAS="${REPLICAS:-1}"

# broker 起来到能接受请求之间有几秒窗口，重试几次而不是直接失败
attempt=0
describe() { $K $B --describe --topic "$TOPIC" 2>/dev/null; }

until [ -n "$(describe)" ]; do
  attempt=$((attempt + 1))
  [ "$attempt" -ge 12 ] && { echo "kafka 未就绪：describe 失败"; exit 1; }
  sleep 5
done

CURRENT=$(describe | sed -n 's/.*PartitionCount:[[:space:]]*\([0-9]*\).*/\1/p' | head -1)
CURRENT=${CURRENT:-0}

if [ "$CURRENT" = "0" ]; then
  echo "创建 topic $TOPIC（partitions=$PARTITIONS replicas=$REPLICAS）"
  $K $B --create --if-not-exists --topic "$TOPIC" \
       --partitions "$PARTITIONS" --replication-factor "$REPLICAS" || exit 1
elif [ "$CURRENT" -lt "$PARTITIONS" ]; then
  echo "扩容 topic $TOPIC 分区：$CURRENT → $PARTITIONS"
  $K $B --alter --topic "$TOPIC" --partitions "$PARTITIONS" || exit 1
else
  echo "topic $TOPIC 已有 $CURRENT 个分区（≥ 期望 $PARTITIONS），不动"
fi

# 最终核对：分区数达标才算成功（Kafka 只增不减，所以调小不会到这行）
FINAL=$(describe | sed -n 's/.*PartitionCount:[[:space:]]*\([0-9]*\).*/\1/p' | head -1)
[ "${FINAL:-0}" -ge "$PARTITIONS" ] || { echo "分区数不符：实际 ${FINAL:-未知} < 期望 $PARTITIONS"; exit 1; }
echo "OK：topic $TOPIC 分区数 $(printf %s "$FINAL")"
