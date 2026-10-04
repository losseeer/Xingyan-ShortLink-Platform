package com.xingyan.shortlink.admin.link;

import com.xingyan.shortlink.common.route.RouteRule;

import java.util.List;

/**
 * PUT /api/v1/links/{code}/rules（DESIGN 6.2 双轨的管理入口）。
 *
 * <p>整体替换而不是增量 patch：规则集合是有顺序语义的整体（priority 冲突、default 唯一），
 * 逐条 patch 的合并语义容易让人配出一套自己也没法预测结果的规则集。
 */
public record UpdateRulesRequest(List<RouteRule> rules) {
}
