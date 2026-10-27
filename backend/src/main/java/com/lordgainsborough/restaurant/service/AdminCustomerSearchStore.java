package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.dto.AdminCustomerSearchResult;
import java.util.List;

/** Read-only registered-customer search and contact-based order histories. */
public interface AdminCustomerSearchStore {
    List<AdminCustomerSearchResult> search(String rawQuery);
}
