import React, { createContext, useContext, useState, useCallback } from 'react';

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
  const [user, setUser] = useState<UserInfo | null>(null);

  const login = useCallback(async (username: string, password: string) => {
    const resp = await fetch('/api/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    const json = await resp.json();
    if (json.code !== 0) throw new Error(json.message);
    const u = json.data as UserInfo;
    setUser(u);
    return u;
  }, []);

  const logout = useCallback(() => setUser(null), []);

  return (
    <AuthContext.Provider value={{ user, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
