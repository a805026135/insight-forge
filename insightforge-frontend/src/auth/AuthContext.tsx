import React, { createContext, useContext, useState, useCallback } from 'react';
import { setToken, clearToken } from './token';

export interface UserInfo {
  id: number;
  username: string;
  role: 'user' | 'merchant';
  displayName: string;
}

interface AuthCtx {
  user: UserInfo | null;
  login: (username: string, password: string) => Promise<UserInfo>;
  logout: () => void;
}

const AuthContext = createContext<AuthCtx>(null!);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<UserInfo | null>(() => {
    try {
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
    setUser(u);
    setToken(u.token);
    localStorage.setItem('insightforge_user', JSON.stringify(u));
    return u;
  }, []);

  const logout = useCallback(() => {
    setUser(null);
    clearToken();
    localStorage.removeItem('insightforge_user');
  }, []);

  return (
    <AuthContext.Provider value={{ user, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
