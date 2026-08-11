import React, { createContext, useContext, useState, useCallback } from 'react';
import { setToken, clearToken, setTokenExpiresAt, isTokenExpired } from './token';

export interface UserInfo {
  id: number;
  username: string;
  role: 'user' | 'merchant';
  displayName: string;
}

interface AuthCtx {
  user: UserInfo | null;
  login: (username: string, password: string) => Promise<UserInfo>;
  register: (username: string, password: string, displayName?: string) => Promise<UserInfo>;
  logout: () => void;
}

const AuthContext = createContext<AuthCtx>(null!);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<UserInfo | null>(() => {
    try {
      // 令牌已过期：立即视为未登录，避免“隔天打开仍免登录”
      if (isTokenExpired()) {
        clearToken();
        localStorage.removeItem('insightforge_user');
        return null;
      }
      const raw = localStorage.getItem('insightforge_user');
      return raw ? (JSON.parse(raw) as UserInfo) : null;
    } catch {
      return null;
    }
  });

  const login = useCallback(async (username: string, password: string) => {
    const resp = await fetch('/api/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    const json = await resp.json();
    if (json.code !== 0) throw new Error(json.message);
    const u = json.data as UserInfo & { token: string };
    const expiresAt = (json.data as any).tokenExpiresAt ?? null;
    setUser(u);
    setToken(u.token);
    setTokenExpiresAt(expiresAt);
    localStorage.setItem('insightforge_user', JSON.stringify(u));
    return u;
  }, []);

  const register = useCallback(
    async (username: string, password: string, displayName?: string) => {
      const resp = await fetch('/api/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password, displayName }),
      });
      const json = await resp.json();
      if (json.code !== 0) throw new Error(json.message);
      const u = json.data as UserInfo & { token: string };
      const expiresAt = (json.data as any).tokenExpiresAt ?? null;
      setUser(u);
      setToken(u.token);
      setTokenExpiresAt(expiresAt);
      localStorage.setItem('insightforge_user', JSON.stringify(u));
      return u;
    },
    [],
  );

  const logout = useCallback(() => {
    setUser(null);
    clearToken();
    localStorage.removeItem('insightforge_user');
  }, []);

  return (
    <AuthContext.Provider value={{ user, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
