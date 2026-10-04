package com.xingyan.shortlink.jump;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 一次点击的决议入口（DESIGN 5.2/7.1）。
 *
 * <p>两个实现按 {@code xsl.jump.mode} 条件装配选一（DESIGN 6.2「真开关」）：
 * {@link Direct302Resolver}（{@code direct302}，永远 302）与 {@link DynamicRouteResolver}
 * （{@code dynamic}，默认，按 {@code route_json.rules} 做一级决策）。两者共用 {@link JumpChain}
 * 那条链路，只有"去哪儿"这一步不同。
 */
public interface JumpResolver {

    JumpDecision resolve(String code, HttpServletRequest request);
}
