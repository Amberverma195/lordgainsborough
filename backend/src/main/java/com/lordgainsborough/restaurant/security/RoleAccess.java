package com.lordgainsborough.restaurant.security;

public final class RoleAccess {
    private RoleAccess() {
    }

    public static boolean isAdmin(String role) {
        return "ADMIN".equals(role);
    }

    public static boolean isStaff(String role) {
        return "STAFF".equals(role);
    }

    public static boolean canAccessKds(String role) {
        return isAdmin(role) || isStaff(role);
    }
}
