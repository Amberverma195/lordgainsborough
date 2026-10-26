package com.lordgainsborough.restaurant.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

@Entity
@Table(name = "store_settings")
public class JpaStoreSettings {

    public static final String DEFAULT_ID = "default";

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id = DEFAULT_ID;

    @Column(name = "tax_rate", precision = 5, scale = 4, nullable = false)
    private BigDecimal taxRate = new BigDecimal("0.1300");

    @Column(name = "default_ready_minutes", nullable = false)
    private int defaultReadyMinutes = 25;

    @Column(name = "store_name", length = 255, nullable = false)
    private String storeName = "Lord Gainsborough";

    @Column(name = "contact_phone", length = 50)
    private String contactPhone = "";

    @Column(name = "contact_email", length = 255)
    private String contactEmail = "";

    @Column(name = "pickup_orders_enabled", nullable = false)
    private boolean pickupOrdersEnabled = true;

    @Column(name = "kds_allowed_ip", columnDefinition = "TEXT")
    private String kdsAllowedIp = "";

    public JpaStoreSettings() {}

    public JpaStoreSettings(String id, BigDecimal taxRate, int defaultReadyMinutes, String storeName) {
        this.id = id != null ? id : DEFAULT_ID;
        this.taxRate = taxRate != null ? taxRate : new BigDecimal("0.1300");
        this.defaultReadyMinutes = defaultReadyMinutes;
        this.storeName = storeName != null ? storeName : "Lord Gainsborough";
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public BigDecimal getTaxRate() { return taxRate; }
    public void setTaxRate(BigDecimal taxRate) { this.taxRate = taxRate; }

    public int getDefaultReadyMinutes() { return defaultReadyMinutes; }
    public void setDefaultReadyMinutes(int defaultReadyMinutes) { this.defaultReadyMinutes = defaultReadyMinutes; }

    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }

    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public boolean isPickupOrdersEnabled() { return pickupOrdersEnabled; }
    public void setPickupOrdersEnabled(boolean pickupOrdersEnabled) { this.pickupOrdersEnabled = pickupOrdersEnabled; }

    public String getKdsAllowedIp() { return kdsAllowedIp; }
    public void setKdsAllowedIp(String kdsAllowedIp) { this.kdsAllowedIp = kdsAllowedIp; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JpaStoreSettings that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
