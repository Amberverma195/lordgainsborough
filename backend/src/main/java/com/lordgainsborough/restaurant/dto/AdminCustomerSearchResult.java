package com.lordgainsborough.restaurant.dto;

import com.lordgainsborough.restaurant.model.CustomerOrder;

import java.util.ArrayList;
import java.util.List;

public class AdminCustomerSearchResult {
    private String id;
    private String name;
    private String email;
    private String phone;
    private String role;
    private String memberSince;
    private int noShowCount;
    private boolean pickupDisabled;
    private List<CustomerOrder> orders = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getMemberSince() { return memberSince; }
    public void setMemberSince(String memberSince) { this.memberSince = memberSince; }

    public int getNoShowCount() { return noShowCount; }
    public void setNoShowCount(int noShowCount) { this.noShowCount = noShowCount; }

    public boolean isPickupDisabled() { return pickupDisabled; }
    public void setPickupDisabled(boolean pickupDisabled) { this.pickupDisabled = pickupDisabled; }

    public List<CustomerOrder> getOrders() { return orders; }
    public void setOrders(List<CustomerOrder> orders) { this.orders = orders; }
}
