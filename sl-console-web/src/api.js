// 只读接口的取数封装（四条接口见 DESIGN 7.1，可达性与边界见 7.2）
const BASE = '/api/v1/stats'

export class ApiError extends Error {
  constructor(message, status) {
    super(message)
    this.status = status
  }
}

async function get(path, params) {
  const q = new URLSearchParams(params).toString()
  const resp = await fetch(`${BASE}${path}${q ? `?${q}` : ''}`, { headers: { Accept: 'application/json' } })
  let body
  try {
    body = await resp.json()
  } catch {
    throw new ApiError(`数据源不可用（HTTP ${resp.status}）`, resp.status)
  }
  // 信封是管理面统一约定 {code,message,data,request_id}；非 00000 一律当失败，不静默返回空数组
  if (!resp.ok || body.code !== '00000') {
    throw new ApiError(body.message || `请求失败（HTTP ${resp.status}）`, resp.status)
  }
  return body.data
}

function windowParams({ from, to, granularity }) {
  const p = {}
  if (from) p.from = from
  if (to) p.to = to
  if (granularity) p.granularity = granularity
  return p
}

export const api = {
  link: (code, w) => get(`/links/${encodeURIComponent(code)}`, windowParams(w)),
  tenantLinks: (tenant, w, limit, offset) =>
    get(`/tenants/${tenant}/links`, { ...windowParams(w), limit, offset }),
  tenantTimeseries: (tenant, w) => get(`/tenants/${tenant}/timeseries`, windowParams(w)),
  tenantChannels: (tenant, w) => get(`/tenants/${tenant}/channels`, windowParams(w)),
  campaign: (id, w) => get(`/campaigns/${encodeURIComponent(id)}`, windowParams(w))
}

// 时间串按后端约定用 ISO 本地形式（'YYYY-MM-DDTHH:mm:ss'）；datetime-local 控件给的就是这个前缀
export function toBackendInput(local) {
  return local ? local.length === 16 ? `${local}:00` : local : ''
}
