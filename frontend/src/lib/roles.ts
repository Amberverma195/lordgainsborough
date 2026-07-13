export function isAdminRole(role?: string | null): boolean {
  return role === 'ADMIN';
}

export function isStaffRole(role?: string | null): boolean {
  return role === 'STAFF';
}

export function canAccessKds(role?: string | null): boolean {
  return isAdminRole(role) || isStaffRole(role);
}
