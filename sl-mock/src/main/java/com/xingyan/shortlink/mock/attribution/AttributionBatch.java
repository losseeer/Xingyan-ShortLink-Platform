package com.xingyan.shortlink.mock.attribution;

import com.xingyan.shortlink.common.attribution.AttributionStat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 归因回传的 batch 信封（DESIGN 7.3，M3-03）。
 *
 * @param batchId   内容寻址的批次号，接收端会<b>重算</b>比对，不信发送端说了算
 * @param tenantId  租户身份；与"这把 api key 属于哪个租户"对齐后才收（items 里没有租户字段）
 * @param from      窗口起点（分钟对齐，含）
 * @param to        窗口终点（分钟对齐，不含）
 * @param checksum  sha1(规范化 items)，接收端重算，不符直接拒收
 * @param items     分钟桶，顺序无关（规范化时会排序）
 */
public record AttributionBatch(String batchId, long tenantId, LocalDateTime from, LocalDateTime to,
                               String checksum, List<AttributionStat> items) {
}
