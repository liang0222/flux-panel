/**
 * 转发地址格式化与校验工具
 *
 * 这些函数原本内联在 forward.tsx（2000+ 行）中，属于纯函数、
 * 不依赖任何 React 状态，抽离出来有三个好处：
 *   1. 降低页面文件体积与认知负担
 *   2. 可被其他页面（如节点页）复用
 *   3. 可独立做单元测试
 *
 * 行为与原实现完全一致，未做任何逻辑改动。
 */

/** 把逗号/换行分隔的地址串拆成有效地址数组（去空白、去空项） */
export function splitAddresses(input: string | null | undefined, separator: ',' | '\n' = ','): string[] {
  if (!input) return [];
  return input
    .split(separator)
    .map(item => item.trim())
    .filter(Boolean);
}

/**
 * 格式化入口地址。
 *
 * 单个地址：`ip:port`；IPv6 会自动加方括号 `[ip]:port`。
 * 多个地址：只显示第一个并追加 `(+N)`，避免卡片被撑开。
 */
export function formatInAddress(ipString: string, port: number): string {
  if (!ipString || !port) return '';

  const ips = splitAddresses(ipString);
  if (ips.length === 0) return '';

  const wrapIfIpv6 = (ip: string) =>
    ip.includes(':') && !ip.startsWith('[') ? `[${ip}]` : ip;

  if (ips.length === 1) {
    return `${wrapIfIpv6(ips[0])}:${port}`;
  }

  return `${wrapIfIpv6(ips[0])}:${port} (+${ips.length - 1})`;
}

/**
 * 格式化远程地址（可能是逗号分隔的多个目标）。
 * 多个时只显示第一个并追加 `(+N)`。
 */
export function formatRemoteAddress(addressString: string): string {
  const addresses = splitAddresses(addressString);
  if (addresses.length === 0) return '';
  if (addresses.length === 1) return addresses[0];
  return `${addresses[0]} (+${addresses.length - 1})`;
}

/** 是否包含多个地址（用于决定是否显示「查看全部」按钮） */
export function hasMultipleAddresses(addressString: string): boolean {
  return splitAddresses(addressString).length > 1;
}

/**
 * 统计地址数量。
 *
 * 注意：这里按【换行】切分，与上面几个函数按逗号切分不同，
 * 因为该函数用于统计多行文本域里填写的条数，保持与原实现一致。
 */
export function getAddressCount(addressString: string): number {
  if (!addressString) return 0;
  return addressString
    .split('\n')
    .map(addr => addr.trim())
    .filter(Boolean)
    .length;
}

/**
 * 把多行文本域内容解析为地址数组（用于导入/批量填写场景）。
 */
export function parseMultilineAddresses(input: string): string[] {
  return splitAddresses(input, '\n');
}
