package com.owly.pricetracker.models;

public class PriceSnapshot {
    private String id;
    private String productId;
    private double price;
    private String sourceAccount;
    private String tweetExcerpt;
    private String tweetUrl;
    private String capturedAt;

    public PriceSnapshot() {}

    public PriceSnapshot(double price, String sourceAccount, String tweetExcerpt, String tweetUrl) {
        this.price = price;
        this.sourceAccount = sourceAccount;
        this.tweetExcerpt = tweetExcerpt;
        this.tweetUrl = tweetUrl;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }

    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }

    public String getSourceAccount() { return sourceAccount; }
    public void setSourceAccount(String sourceAccount) { this.sourceAccount = sourceAccount; }

    public String getTweetExcerpt() { return tweetExcerpt; }
    public void setTweetExcerpt(String tweetExcerpt) { this.tweetExcerpt = tweetExcerpt; }

    public String getTweetUrl() { return tweetUrl; }
    public void setTweetUrl(String tweetUrl) { this.tweetUrl = tweetUrl; }

    public String getCapturedAt() { return capturedAt; }
    public void setCapturedAt(String capturedAt) { this.capturedAt = capturedAt; }
}
