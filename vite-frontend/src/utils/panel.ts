export interface PanelAddress {
  name: string;
  address: string;
  inx: boolean;
}

type PanelRequest =
  | { action: 'list' }
  | { action: 'save'; name: string; address: string }
  | { action: 'select' | 'delete'; name: string };

interface PanelReply {
  ok: boolean;
  addresses?: PanelAddress[];
}

interface PanelHost extends Window {
  FluxPanel?: {
    postMessage(message: string): void;
    onmessage: ((event: MessageEvent<string>) => void) | null;
  };
  webkit?: { messageHandlers?: {
    FluxPanel?: { postMessage(message: PanelRequest): Promise<PanelReply> };
    FluxSession?: { postMessage(message: { action: string; panel?: string; session?: Session }): Promise<SessionReply> };
  } };
}

type Session = Record<'token' | 'role_id' | 'name' | 'admin', string>;
interface SessionReply {
  ok: boolean;
  session?: Session;
}

const host = window as PanelHost;
let nextRequestId = 0;

export async function iosSessionRequest(action: 'load' | 'save' | 'clear', panel?: string, session?: Session) {
  return host.webkit?.messageHandlers?.FluxSession?.postMessage({ action, ...(panel ? { panel } : {}), ...(session ? { session } : {}) });
}

export function isWebViewFunc(): boolean {
  return typeof host.FluxPanel?.postMessage === 'function' ||
    typeof host.webkit?.messageHandlers?.FluxPanel?.postMessage === 'function';
}

async function sendPanelRequest(request: PanelRequest): Promise<PanelAddress[]> {
  const android = host.FluxPanel;
  const ios = host.webkit?.messageHandlers?.FluxPanel;
  let reply: PanelReply;
  if (android) {
    // The shell replies on the origin-bound message channel. Keep one listener
    // so concurrent callers can match replies by ID without replacing callbacks.
    android.onmessage = receiveAndroidReply;
    const id = ++nextRequestId;
    reply = await new Promise<PanelReply>((resolve, reject) => {
      const finish = (value?: PanelReply) => {
        clearTimeout(timer);
        pendingAndroidReplies.delete(id);
        if (value) resolve(value);
        else reject(new Error('面板设置服务暂不可用'));
      };
      const timer = setTimeout(finish, 5000);
      pendingAndroidReplies.set(id, finish);
      try {
        android.postMessage(JSON.stringify({ ...request, id }));
      } catch {
        finish();
      }
    });
  } else if (ios) {
    reply = await ios.postMessage(request);
  } else {
    throw new Error('面板设置仅在移动客户端可用');
  }

  if (!reply?.ok || !Array.isArray(reply.addresses)) {
    throw new Error('操作未保存，请检查面板名称和地址');
  }
  return reply.addresses;
}

const pendingAndroidReplies = new Map<number, (reply?: PanelReply) => void>();

function receiveAndroidReply(event: MessageEvent<string>) {
  try {
    const reply = JSON.parse(event.data);
    pendingAndroidReplies.get(reply?.id)?.(reply);
  } catch {
    // Ignore unrecognized bridge messages. The request deadline still applies.
  }
}

export const getPanelAddresses = () => sendPanelRequest({ action: 'list' });
export const savePanelAddress = (name: string, address: string) => sendPanelRequest({ action: 'save', name, address });
export const setCurrentPanelAddress = (name: string) => sendPanelRequest({ action: 'select', name });
export const deletePanelAddress = (name: string) => sendPanelRequest({ action: 'delete', name });

// Keep explicit ports and reverse-proxy path prefixes. Query, fragment and
// userinfo are not part of a panel base URL.
export function normalizePanelAddress(address: string): string | null {
  const value = address.trim();
  if (!/^https:\/\/[^/]/i.test(value) || /[\s\\?#]/.test(value) || value.length > 2048) return null;
  try {
    const url = new URL(value);
    if (!url.hostname || url.hostname === 'appassets.androidplatform.net' ||
        url.username || url.password || url.port === '0') return null;
    return `${url.origin}${url.pathname.replace(/\/+$/, '')}`;
  } catch {
    return null;
  }
}
