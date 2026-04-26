package com.promo.tracker.models;

public class Product {
    private String id;
    private String name;
    private String normalizedName;
    private String language;
    private String status; // idle, loading, success, error
    private Double currentPrice;
    private String lastUpdated;
    private String createdAt;

    // Watch info
    private String watchId;
    private Double targetPrice;
    private String watchStatus;

    // Transient UI state
    private boolean analyzing = false;

    public Product() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getNormalizedName() { return normalizedName; }
    public void setNormalizedName(String v) { this.normalizedName = v; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getStatus() { return status != null ? status : "idle"; }
    public void setStatus(String status) { this.status = status; }

    public Double getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(Double currentPrice) { this.currentPrice = currentPrice; }

    public String getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(String lastUpdated) { this.lastUpdated = lastUpdated; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getWatchId() { return watchId; }
    public void setWatchId(String watchId) { this.watchId = watchId; }

    public Double getTargetPrice() { return targetPrice; }
    public void setTargetPrice(Double targetPrice) { this.targetPrice = targetPrice; }

    public String getWatchStatus() { return watchStatus; }
    public void setWatchStatus(String watchStatus) { this.watchStatus = watchStatus; }

    public boolean isAnalyzing() { return analyzing; }
    public void setAnalyzing(boolean analyzing) { this.analyzing = analyzing; }

    public boolean isTargetReached() {
        return targetPrice != null && currentPrice != null && currentPrice <= targetPrice;
    }
}
