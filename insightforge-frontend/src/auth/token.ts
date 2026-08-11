// JWT 的本地存取（Phase 14.3 鉴权）
const TOKEN_KEY = 'insightforge_token';
const TOKEN_EXPIRES_KEY = 'insightforge_token_expires_at';

export function getTokenExpiresAt(): number | null {
  const raw = localStorage.getItem(TOKEN_EXPIRES_KEY);
  if (!raw) return null;
  const t = Number(raw);
  return Number.isFinite(t) ? t : null;
}

export function isTokenExpired(): boolean {
  const exp = getTokenExpiresAt();
  if (exp == null) return true;
  return Date.now() >= exp;
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function setTokenExpiresAt(expiresAt: number | null) {
  if (expiresAt == null) {
    localStorage.removeItem(TOKEN_EXPIRES_KEY);
  } else {
    localStorage.setItem(TOKEN_EXPIRES_KEY, String(expiresAt));
  }
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(TOKEN_EXPIRES_KEY);
}
