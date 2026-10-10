/**
 * 地址工具单元测试
 *
 * 运行方式（无需安装任何测试框架，使用 Node 内置 test runner）：
 *   node --experimental-strip-types --test src/utils/address.test.mjs
 *
 * 之所以用 node:test 而不是 vitest/jest：项目当前没有任何测试框架，
 * 引入它们会新增依赖并改动构建链路。这些函数是纯函数，
 * 用 Node 原生 runner 足够，且零依赖。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  splitAddresses,
  formatInAddress,
  formatRemoteAddress,
  hasMultipleAddresses,
  getAddressCount,
  parseMultilineAddresses,
} from './address.ts';

// ---------------------------------------------------------------------------
// splitAddresses
// ---------------------------------------------------------------------------
test('splitAddresses：拆分并去除空白与空项', () => {
  assert.deepEqual(splitAddresses('1.1.1.1, 2.2.2.2 ,3.3.3.3'), ['1.1.1.1', '2.2.2.2', '3.3.3.3']);
  assert.deepEqual(splitAddresses('1.1.1.1,,2.2.2.2'), ['1.1.1.1', '2.2.2.2']);
  assert.deepEqual(splitAddresses('  '), []);
  assert.deepEqual(splitAddresses(''), []);
  assert.deepEqual(splitAddresses(null), []);
  assert.deepEqual(splitAddresses(undefined), []);
});

test('splitAddresses：支持按换行拆分', () => {
  assert.deepEqual(splitAddresses('a\nb\n\nc', '\n'), ['a', 'b', 'c']);
});

// ---------------------------------------------------------------------------
// formatInAddress
// ---------------------------------------------------------------------------
test('formatInAddress：单个 IPv4', () => {
  assert.equal(formatInAddress('1.2.3.4', 8080), '1.2.3.4:8080');
});

test('formatInAddress：IPv6 自动加方括号', () => {
  assert.equal(formatInAddress('2001:db8::1', 8080), '[2001:db8::1]:8080');
});

test('formatInAddress：已是方括号的 IPv6 不重复包裹', () => {
  assert.equal(formatInAddress('[2001:db8::1]', 8080), '[2001:db8::1]:8080');
});

test('formatInAddress：多个地址显示第一个并追加 (+N)', () => {
  assert.equal(formatInAddress('1.1.1.1,2.2.2.2,3.3.3.3', 80), '1.1.1.1:80 (+2)');
});

test('formatInAddress：多个地址且首个为 IPv6', () => {
  assert.equal(formatInAddress('2001:db8::1,2.2.2.2', 80), '[2001:db8::1]:80 (+1)');
});

test('formatInAddress：空输入或端口为 0 时返回空串', () => {
  assert.equal(formatInAddress('', 80), '');
  assert.equal(formatInAddress('1.2.3.4', 0), '');
});

// ---------------------------------------------------------------------------
// formatRemoteAddress
// ---------------------------------------------------------------------------
test('formatRemoteAddress：单地址原样返回', () => {
  assert.equal(formatRemoteAddress('example.com:443'), 'example.com:443');
});

test('formatRemoteAddress：多地址追加 (+N)', () => {
  assert.equal(formatRemoteAddress('a.com:1,b.com:2'), 'a.com:1 (+1)');
  assert.equal(formatRemoteAddress('a.com:1,b.com:2,c.com:3'), 'a.com:1 (+2)');
});

test('formatRemoteAddress：空输入返回空串', () => {
  assert.equal(formatRemoteAddress(''), '');
  assert.equal(formatRemoteAddress('  ,  '), '');
});

// ---------------------------------------------------------------------------
// hasMultipleAddresses
// ---------------------------------------------------------------------------
test('hasMultipleAddresses：正确判断数量', () => {
  assert.equal(hasMultipleAddresses('a:1'), false);
  assert.equal(hasMultipleAddresses('a:1,b:2'), true);
  assert.equal(hasMultipleAddresses(''), false);
  assert.equal(hasMultipleAddresses('a:1,'), false); // 尾部空项不计入
});

// ---------------------------------------------------------------------------
// getAddressCount（按换行统计，与原实现一致）
// ---------------------------------------------------------------------------
test('getAddressCount：按换行计数', () => {
  assert.equal(getAddressCount('a\nb\nc'), 3);
  assert.equal(getAddressCount('a\n\n\nb'), 2);
  assert.equal(getAddressCount(''), 0);
});

test('getAddressCount：单个多行文本中的逗号不拆分', () => {
  // 该函数用于统计文本域行数，一行内即使有逗号也算一条
  assert.equal(getAddressCount('a,b\nc'), 2);
});

// ---------------------------------------------------------------------------
// parseMultilineAddresses
// ---------------------------------------------------------------------------
test('parseMultilineAddresses：解析多行文本域', () => {
  assert.deepEqual(parseMultilineAddresses('1.1.1.1\n2.2.2.2\n\n3.3.3.3'), [
    '1.1.1.1',
    '2.2.2.2',
    '3.3.3.3',
  ]);
});
