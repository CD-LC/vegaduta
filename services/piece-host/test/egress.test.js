import assert from 'node:assert/strict';
import test from 'node:test';
import { expandIPv6, installEgressGuard, isBlockedAddress, makeGuardedLookup } from '../src/egress.js';

test('IPv4: private, loopback, link-local (cloud metadata), CGNAT, multicast are blocked', () => {
  for (const ip of ['127.0.0.1', '127.255.255.254', '10.0.0.1', '172.16.0.1', '172.31.255.255', '192.168.1.1', '169.254.169.254', '100.64.0.1', '0.0.0.0', '224.0.0.1', '255.255.255.255', '198.18.0.1']) {
    assert.equal(isBlockedAddress(ip), true, ip);
  }
});

test('IPv4: ordinary public addresses and range edges are allowed', () => {
  for (const ip of ['8.8.8.8', '1.1.1.1', '172.15.255.255', '172.32.0.1', '100.63.255.255', '100.128.0.1', '192.167.1.1', '169.253.1.1']) {
    assert.equal(isBlockedAddress(ip), false, ip);
  }
});

test('IPv6: loopback, unspecified, ULA, link-local, multicast and IPv4-mapped private are blocked', () => {
  for (const ip of ['::1', '::', 'fc00::1', 'fd12:3456::1', 'fe80::1', 'febf::1', 'ff02::1', '::ffff:127.0.0.1', '::ffff:7f00:1', '::ffff:10.0.0.1', '64:ff9b::a00:1']) {
    assert.equal(isBlockedAddress(ip), true, ip);
  }
});

test('IPv6: public addresses and IPv4-mapped public addresses are allowed', () => {
  for (const ip of ['2606:4700:4700::1111', '2a00:1450:4001::200e', '::ffff:8.8.8.8']) {
    assert.equal(isBlockedAddress(ip), false, ip);
  }
});

test('garbage fails closed', () => {
  for (const bad of ['not-an-ip', '', '999.1.1.1', null, undefined, 42]) assert.equal(isBlockedAddress(bad), true, String(bad));
});

test('expandIPv6 handles compression and embedded IPv4', () => {
  assert.deepEqual(expandIPv6('::1'), [0, 0, 0, 0, 0, 0, 0, 1]);
  assert.deepEqual(expandIPv6('1:2:3:4:5:6:7:8'), [1, 2, 3, 4, 5, 6, 7, 8]);
  assert.deepEqual(expandIPv6('::ffff:1.2.3.4'), [0, 0, 0, 0, 0, 0xffff, 0x0102, 0x0304]);
  assert.deepEqual(expandIPv6('fe80::1%eth0'), [0xfe80, 0, 0, 0, 0, 0, 0, 1]);
  assert.equal(expandIPv6('1::2::3'), null);
});

function lookupStub(result) {
  return (hostname, options, cb) => cb(null, result.address, result.family);
}

test('guarded lookup rejects non-public resolutions and passes public ones through', async () => {
  const bad = makeGuardedLookup(lookupStub({ address: '10.0.0.5', family: 4 }));
  const err = await new Promise((resolve) => bad('internal.example', {}, (e) => resolve(e)));
  assert.equal(err.code, 'EPIECEHOST_EGRESS_BLOCKED');

  const good = makeGuardedLookup(lookupStub({ address: '8.8.8.8', family: 4 }));
  const [e2, address, family] = await new Promise((resolve) => good('ok.example', {}, (...args) => resolve(args)));
  assert.equal(e2, null);
  assert.equal(address, '8.8.8.8');
  assert.equal(family, 4);
});

test('guarded lookup supports the callback-only form and all:true results', async () => {
  const mixed = makeGuardedLookup((h, o, cb) => cb(null, [{ address: '8.8.8.8', family: 4 }, { address: '127.0.0.1', family: 4 }]));
  const err = await new Promise((resolve) => mixed('x.example', { all: true }, (e) => resolve(e)));
  assert.equal(err.code, 'EPIECEHOST_EGRESS_BLOCKED');

  const cbOnly = makeGuardedLookup((h, o, cb) => cb(null, '1.1.1.1', 4));
  const args = await new Promise((resolve) => cbOnly('y.example', (...a) => resolve(a)));
  assert.equal(args[1], '1.1.1.1');
});

test('guarded lookup propagates resolver errors', async () => {
  const failing = makeGuardedLookup((h, o, cb) => cb(new Error('ENOTFOUND')));
  const err = await new Promise((resolve) => failing('z.example', {}, (e) => resolve(e)));
  assert.equal(err.message, 'ENOTFOUND');
});

test('installEgressGuard is a no-op when private egress is explicitly allowed', () => {
  assert.equal(installEgressGuard({ allowPrivate: true }), false);
});
