export interface AuthData {
  token?: string;
  name?: string;
  email?: string;
  phone?: string;
  memberSince?: string;
  role?: string;
}

export function persistAuth(data: AuthData) {
  if (data.token) {
    localStorage.setItem('authToken', data.token);
  }
  if (data.name) {
    localStorage.setItem('userName', data.name);
  }
  if (data.email) {
    localStorage.setItem('userEmail', data.email);
  }
  if (data.phone !== undefined) {
    localStorage.setItem('userPhone', data.phone);
  }
  if (data.memberSince) {
    localStorage.setItem('userMemberSince', data.memberSince);
  }
  if (data.role) {
    localStorage.setItem('userRole', data.role);
    document.cookie = `userRole=${encodeURIComponent(data.role)}; path=/; max-age=${60 * 60 * 24 * 7}; SameSite=Lax`;
  }
}

export function clearAuth() {
  localStorage.removeItem('authToken');
  localStorage.removeItem('userName');
  localStorage.removeItem('userEmail');
  localStorage.removeItem('userPhone');
  localStorage.removeItem('userMemberSince');
  localStorage.removeItem('userRole');
  document.cookie = 'userRole=; path=/; max-age=0; SameSite=Lax';
}

export function getAuthHeaders(includeJson = true): HeadersInit {
  const headers: Record<string, string> = {};
  const token = localStorage.getItem('authToken');
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  if (includeJson) {
    headers['Content-Type'] = 'application/json';
  }
  return headers;
}

export function getAuthHeadersOnly(): HeadersInit {
  const token = localStorage.getItem('authToken');
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export function isAuthenticated(): boolean {
  if (typeof window === 'undefined') return false;
  return !!localStorage.getItem('authToken');
}
