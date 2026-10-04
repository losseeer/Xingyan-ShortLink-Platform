package com.xingyan.shortlink.common.event;

/**
 * ClickEvent 契约（DESIGN 6.3/附录 A 口径，4.2 click_event 列对齐）。
 * SNAKE_CASE 序列化为 Kafka 消息体；event_id 为幂等键（CH ReplacingMergeTree 去重）。
 *
 * <p>M2-32 起 {@code deviceType}（容器）与 {@code os}（设备大类）由 jump 侧 UA 特征表填真实值，
 * 认不出填 {@code unknown} 而不是空串——空串留给"这列没被写过"，两种情况在看板上含义不同。
 * M1 遗留口径：{@code province/city} 仍为空（离线地理库未接入，DESIGN 6.2 的能力边界），
 * {@code riskScore} 只有超频 +25、{@code isBot} 恒 0（评分层未交付）。
 */
public class ClickEvent {

    private String eventId;
    private String shortCode;
    /** epoch 秒，写 CH 时格式化为 DateTime */
    private long clickTime;
    private String ipHash;
    private String userAgent;
    private String referer;
    private String deviceType;
    private String os;
    private String province;
    private String city;
    private String channelId;
    private String campaignId;
    private String promoterId;
    private String traceId;
    /** JSON 对象串（utm_* 透传对） */
    private String utmParams;
    private int riskScore;
    private int isBot;
    private long tenantId;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getShortCode() { return shortCode; }
    public void setShortCode(String shortCode) { this.shortCode = shortCode; }
    public long getClickTime() { return clickTime; }
    public void setClickTime(long clickTime) { this.clickTime = clickTime; }
    public String getIpHash() { return ipHash; }
    public void setIpHash(String ipHash) { this.ipHash = ipHash; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getReferer() { return referer; }
    public void setReferer(String referer) { this.referer = referer; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public String getOs() { return os; }
    public void setOs(String os) { this.os = os; }
    public String getProvince() { return province; }
    public void setProvince(String province) { this.province = province; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }
    public String getCampaignId() { return campaignId; }
    public void setCampaignId(String campaignId) { this.campaignId = campaignId; }
    public String getPromoterId() { return promoterId; }
    public void setPromoterId(String promoterId) { this.promoterId = promoterId; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getUtmParams() { return utmParams; }
    public void setUtmParams(String utmParams) { this.utmParams = utmParams; }
    public int getRiskScore() { return riskScore; }
    public void setRiskScore(int riskScore) { this.riskScore = riskScore; }
    public int getIsBot() { return isBot; }
    public void setIsBot(int isBot) { this.isBot = isBot; }
    public long getTenantId() { return tenantId; }
    public void setTenantId(long tenantId) { this.tenantId = tenantId; }
}
