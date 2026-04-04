package com.owly.pricetracker.models;

public class User {
    private String id;
    private String email;
    private String accessToken;
    private String refreshToken;

    public User() {}

    public User(String id, String email, String accessToken, String refreshToken) {
        this.id = id;
        this.email = email;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

    public String getInitial() {
        if (email != null && !email.isEmpty()) return String.valueOf(email.charAt(0)).toUpperCase();
        return "U";
    }

    public String getDisplayEmail() {
        return email != null ? email : "";
    }
}
