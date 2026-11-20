import { getRoleIdFromToken, isTokenValid } from './jwt';
import { iosSessionRequest } from './panel';

type Session = Record<'token' | 'role_id' | 'name' | 'admin', string>;
const sessionKeys = ['token', 'role_id', 'name', 'admin'] as const;
const panelKey = 'session_panel';

function writeSession(session: Session, panel?: string) {
  if (panel) localStorage.setItem(panelKey, panel);
  for (const key of sessionKeys) localStorage.setItem(key, session[key]);
}

function clearLocalSession() {
  for (const key of sessionKeys) localStorage.removeItem(key);
  localStorage.removeItem(panelKey);
}

export function bindLocalSession(panel?: string) {
  if (!panel || localStorage.getItem(panelKey) !== panel) clearLocalSession();
}

export async function restoreSession(panel: string) {
  const reply = await iosSessionRequest('load', panel);
  if (!reply) return; // Android uses the origin's bound local session.
  clearLocalSession();
  if (reply.ok && reply.session && isTokenValid(reply.session.token)) writeSession(reply.session, panel);
}

export async function saveSession(session: Session, panel?: string, isCurrent = () => true) {
  const reply = panel ? await iosSessionRequest('save', panel, session) : undefined;
  if (reply?.ok === false) throw new Error('登录信息保存失败');
  if (!isCurrent()) throw new Error('面板已切换，请重新登录');
  writeSession(session, panel);
}

export function clearSession() {
  clearLocalSession();
  void iosSessionRequest('clear').catch(() => { /* WebView is closing. */ });
}

/**
 * 权限工具类
 */

/**
 * 获取当前用户的token
 * @returns token
 */
export function getToken(): string | null {
  return localStorage.getItem('token');
}

/**
 * 获取当前用户的角色ID
 * @returns 角色ID
 */
export function getCurrentUserRoleId(): number | null {
  const token = getToken();
  if (!token || !isTokenValid(token)) {
    return null;
  }
  return getRoleIdFromToken(token);
}

/**
 * 判断当前用户是否是管理员
 * @returns 是否是管理员
 */
export function isAdmin(): boolean {
  const roleId = getCurrentUserRoleId();
  return roleId === 0;
}

/**
 * 判断当前用户是否有指定角色
 * @param targetRoleId 目标角色ID
 * @returns 是否有指定角色
 */
export function hasRole(targetRoleId: number): boolean {
  const roleId = getCurrentUserRoleId();
  return roleId === targetRoleId;
}

/**
 * 判断当前用户是否已登录且token有效
 * @returns 是否已登录
 */
export function isLoggedIn(): boolean {
  const token = getToken();
  return token ? isTokenValid(token) : false;
}

/**
 * 权限检查装饰器函数
 * @param fn 要执行的函数
 * @param errorMsg 权限不足时的错误提示
 * @returns 包装后的函数
 */
export function requireAdmin<T extends (...args: any[]) => any>(
  fn: T, 
  errorMsg: string = '权限不足，仅管理员可操作'
): T {
  return ((...args: Parameters<T>) => {
    if (!isAdmin()) {
      console.warn(errorMsg);
      return false;
    }
    return fn(...args);
  }) as T;
} 