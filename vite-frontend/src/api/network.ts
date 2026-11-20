import axios from 'axios';
import type { AxiosResponse } from 'axios';
import { bindLocalSession, clearSession, getToken } from '@/utils/auth';
import { isWebViewFunc, normalizePanelAddress } from '@/utils/panel';
import type { PanelAddress } from '@/utils/panel';

const mobile = import.meta.env.MODE === 'mobile' || isWebViewFunc();
const webBase = import.meta.env.VITE_API_BASE?.replace(/\/+$/, '');
let baseURL = mobile || !webBase ? undefined : `${webBase}/api/v1/`;
if (!mobile && !baseURL) baseURL = '/api/v1/';
let activePanel: string | undefined;
let panelRevision = 0;

// Keep saved Android credentials bound to the exact native selection across
// restarts. The iOS bridge separately binds Keychain entries to the selection.
export function applyPanelAddresses(addresses: PanelAddress[]) {
  if (!mobile) return;
  const current = addresses.find((item) => item?.inx === true);
  const address = typeof current?.address === 'string' && normalizePanelAddress(current.address);
  const nextPanel = address ? current?.address : undefined;
  if (nextPanel !== activePanel) panelRevision++;
  bindLocalSession(nextPanel);
  activePanel = nextPanel;
  baseURL = address ? `${address}/api/v1/` : undefined;
}

export function getBaseURL() {
  return baseURL;
}

export function getActivePanel() {
  return activePanel;
}

export function getPanelRevision() {
  return panelRevision;
}

interface ApiResponse<T = any> {
  code: number;
  msg: string;
  data: T;
}

function handleTokenExpired(token: string | null, revision: number) {
  // Concurrent responses and stale responses from another panel cannot log
  // out a replacement session. Clearing the matching token deduplicates it.
  if (!token || panelRevision !== revision || getToken() !== token) return;
  clearSession();
  if (mobile) {
    if (window.location.hash !== '' && window.location.hash !== '#/') {
      window.location.replace('#/');
    }
  } else if (window.location.pathname !== '/') {
    window.location.replace('/');
  }
}

function isTokenExpired(response: ApiResponse) {
  return response?.code === 401 && [
    '未登录或token已过期',
    '无效的token或token已过期',
    '无法获取用户权限信息',
  ].includes(response.msg);
}

async function request<T>(method: 'get' | 'post', path: string, data: unknown, authenticated: boolean): Promise<ApiResponse<T>> {
  const requestBaseURL = baseURL;
  const revision = panelRevision;
  if (!requestBaseURL) return { code: -1, msg: '请先设置面板地址', data: null as T };

  const token = authenticated ? getToken() : null;
  let response: AxiosResponse<ApiResponse<T>>;
  try {
    response = await axios.request<ApiResponse<T>>({
      method,
      url: path,
      baseURL: requestBaseURL,
      ...(method === 'get' ? { params: data } : { data }),
      timeout: 30000,
      headers: { Authorization: token },
    });
  } catch (error) {
    if (revision !== panelRevision) return { code: -1, msg: '面板已切换，请重试', data: null as T };
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      handleTokenExpired(token, revision);
      return { code: 401, msg: '未登录或登录已过期', data: null as T };
    }
    // An Axios error can contain auth headers, request bodies and credentials.
    return { code: -1, msg: '网络请求失败', data: null as T };
  }

  if (revision !== panelRevision) return { code: -1, msg: '面板已切换，请重试', data: null as T };
  if (isTokenExpired(response.data)) handleTokenExpired(token, revision);
  return response.data;
}

const Network = {
  get<T = any>(path = '', data: unknown = {}, authenticated = true) {
    return request<T>('get', path, data, authenticated);
  },
  post<T = any>(path = '', data: unknown = {}, authenticated = true) {
    return request<T>('post', path, data, authenticated);
  },
};

export default Network;
