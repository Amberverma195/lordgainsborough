package com.lordgainsborough.restaurant.model;

public class StoreSettings {
    public static final String DEFAULT_ID = "default";

    private String id = DEFAULT_ID;
    private double taxRate = 0.13;
    private int defaultReadyMinutes = 25;
    private String storeName = "Lord Gainsborough";
    private String contactPhone = "";
    private String contactEmail = "";
    private boolean pickupOrdersEnabled = true;

    /**
     * Store / kitchen public IP(s) allowed to open KDS. One per line or comma-separated.
     * Empty means KDS is locked for everyone.
     */
    private String kdsAllowedIp = "";

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public double getTaxRate() { return taxRate; }
    public void setTaxRate(double taxRate) { this.taxRate = taxRate; }

    public int getDefaultReadyMinutes() { return defaultReadyMinutes; }
    public void setDefaultReadyMinutes(int defaultReadyMinutes) { this.defaultReadyMinutes = defaultReadyMinutes; }

    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }

    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public boolean getPickupOrdersEnabled() { return pickupOrdersEnabled; }
    public void setPickupOrdersEnabled(boolean pickupOrdersEnabled) { this.pickupOrdersEnabled = pickupOrdersEnabled; }

    public String getKdsAllowedIp() { return kdsAllowedIp; }
    public void setKdsAllowedIp(String kdsAllowedIp) { this.kdsAllowedIp = kdsAllowedIp; }
}
