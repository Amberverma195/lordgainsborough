package com.lordgainsborough.restaurant.service;

public interface CustomerOrderCountStore {
    long byEmail(String email, boolean noShowsOnly);
    long byPhone(String phone, boolean noShowsOnly);
}
