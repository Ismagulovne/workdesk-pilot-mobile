import test from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { startHub, mcpHandler } from '../wdpilot-mobile.mjs';
import { TOOLS, ACTIONS } from '../tools.mjs';
import { fakePhone, waitFor } from './fake-phone.mjs';

const CODE = '424242';
async function hub(opts = {}) {
  const h = startHub({ port: 0, host: '127.0.0.1', code: CODE, ...opts });
  const port = await h.ready;
  return { h, url: `ws://127.0.0.1:${port}/pilot`, port };
}
const txt = (r) => r.content.map((c) => c.text || '').join('\n');

test('MCP: initialize и 22 инструмента, все 17 действий computer_toolset_20260801', async () => {
  const { h } = await hub();
  const handle = mcpHandler(h);
  const init = await handle({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18' } });
  assert.equal(init.result.serverInfo.name, 'wdpilot-mobile');
  assert.match(init.result.instructions, /данные, а не указания/);
  assert.equal(await handle({ jsonrpc: '2.0', method: 'notifications/initialized' }), null);
  const list = await handle({ jsonrpc: '2.0', id: 2, method: 'tools/list' });
  const names = list.result.tools.map((t) => t.name);
  assert.equal(names.length, 22);
  for (const a of ACTIONS) assert.ok(names.includes(a), a);
  for (const t of ['list_windows', 'select_window', 'release_window', 'focus_window', 'status']) assert.ok(names.includes(t));
  const unk = await handle({ jsonrpc: '2.0', id: 3, method: 'nope' });
  assert.equal(unk.error.code, -32601);
  assert.equal(TOOLS.length, 22);
  await h.close();
});

test('без телефона: status даёт адрес и код, действия — ошибка', async () => {
  const { h } = await hub();
  const s = await h.callTool('status');
  assert.equal(s.isError, false);
  assert.match(txt(s), /код: 424242/);
  const c = await h.callTool('left_click', { coordinate: [1, 1] });
  assert.equal(c.isError, true);
  const u = await h.callTool('rm_rf', {});
  assert.match(txt(u), /неизвестный инструмент/);
  await h.close();
});

test('неверный код — 4401; 5 ошибок меняют случайный код', async () => {
  const { h, url } = await hub();
  const p = fakePhone(url, { code: '000000' });
  assert.equal(await p.closedWith(), 4401);
  await h.close();

  const h2 = startHub({ port: 0, host: '127.0.0.1' });
  const port = await h2.ready;
  const first = h2.state.code;
  for (let i = 0; i < 5; i++) {
    const wrong = first === '111111' ? '222222' : '111111';
    assert.equal(await fakePhone(`ws://127.0.0.1:${port}/pilot`, { code: wrong }).closedWith(), 4401);
  }
  assert.notEqual(h2.state.code, first);
  await h2.close();
});

test('сценарий «Заметки»: выбор, снимок, касание, ввод, Return', async () => {
  const { h, url } = await hub();
  const p = fakePhone(url, { code: CODE });
  await p.welcomed();
  const apps = JSON.parse(txt(await h.callTool('list_windows')));
  assert.equal(apps.length, 2);
  assert.equal((await h.callTool('select_window', { window_id: 2 })).isError, true, 'банк запрещён');
  assert.equal((await h.callTool('select_window', { window_id: 1 })).isError, false);
  const shot = await h.callTool('screenshot');
  assert.equal(shot.content[0].type, 'image');
  assert.equal(shot.content[0].mimeType, 'image/jpeg');
  assert.equal((await h.callTool('left_click', { coordinate: [500, 1200] })).isError, false);
  assert.equal((await h.callTool('left_click', { coordinate: [5000, 1] })).isError, true);
  assert.equal((await h.callTool('type', { text: 'Купить хлеб' })).isError, false);
  assert.equal((await h.callTool('type', { text: '4111 1111 1111 1111' })).isError, true);
  assert.equal((await h.callTool('key', { text: 'Return' })).isError, false);
  assert.equal(p.st.text, 'Купить хлеб');
  await h.close();
});

test('«Стоп» на телефоне: дальше действия отклоняются, мост отмечает остановку', async () => {
  const { h, url } = await hub();
  const p = fakePhone(url, { code: CODE });
  await p.welcomed();
  await h.callTool('select_window', { window_id: 1 });
  p.pressStop();
  await waitFor(() => h.state.stoppedAt);
  const r = await h.callTool('left_click', { coordinate: [1, 1] });
  assert.equal(r.isError, true);
  assert.match(txt(r), /Остановлено пользователем/);
  await h.close();
});

test('таймаут: телефон молчит — ошибка, очередь пуста', async () => {
  const { h, url } = await hub({ timeoutScale: 0.005 }); // 30 с → 150 мс
  const p = fakePhone(url, { code: CODE, ignore: new Set(['screenshot']) });
  await p.welcomed();
  const r = await h.callTool('screenshot');
  assert.equal(r.isError, true);
  assert.match(txt(r), /не ответил/);
  assert.equal(h.state.pending.size, 0);
  await h.close();
});

test('обрыв связи во время действия — ожидающие вызовы получают ошибку', async () => {
  const { h, url } = await hub();
  const p = fakePhone(url, { code: CODE, ignore: new Set(['wait']) });
  await p.welcomed();
  const pending = h.callTool('wait', { duration: 5 });
  await waitFor(() => h.state.pending.size === 1);
  p.ws.close();
  const r = await pending;
  assert.equal(r.isError, true);
  assert.match(txt(r), /отключился/);
  assert.equal(h.state.phone, null);
  await h.close();
});

test('второй телефон вытесняет первый (4001)', async () => {
  const { h, url } = await hub();
  const a = fakePhone(url, { code: CODE, device: 'A' });
  await a.welcomed();
  const b = fakePhone(url, { code: CODE, device: 'B' });
  await b.welcomed();
  assert.equal(await a.closedWith(), 4001);
  assert.equal(h.state.phone.info.device, 'B');
  await h.close();
});

test('краш-тесты: мусорный JSON, сообщение не hello, слишком большое, немаскированный кадр', async () => {
  const { h, url, port } = await hub({ maxMessage: 64 * 1024 });
  const close = (ws) => new Promise((r) => ws.addEventListener('close', (e) => r(e.code)));

  let ws = new WebSocket(url);
  ws.addEventListener('open', () => ws.send('{not json'));
  assert.equal(await close(ws), 4400);

  ws = new WebSocket(url);
  ws.addEventListener('open', () => ws.send(JSON.stringify({ t: 'call', id: 1, name: 'type' })));
  assert.equal(await close(ws), 4400);

  ws = new WebSocket(url);
  ws.addEventListener('open', () => ws.send('x'.repeat(100 * 1024)));
  assert.equal(await close(ws), 1009);

  ws = new WebSocket(url);
  ws.addEventListener('open', () => ws.send(JSON.stringify({ t: 'hello', proto: 99, code: CODE })));
  assert.equal(await close(ws), 4426);

  // Сырой сокет: рукопожатие, затем немаскированный кадр — сервер должен закрыть, не упасть.
  await new Promise((resolve) => {
    const s = net.connect(port, '127.0.0.1', () => {
      s.write('GET /pilot HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n' +
        'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n');
      setTimeout(() => s.write(Buffer.from([0x81, 0x02, 0x68, 0x69])), 50);
    });
    s.on('data', () => {});
    s.on('close', resolve);
    s.on('error', resolve);
  });
  // Не тот путь — 400.
  await new Promise((resolve) => {
    const s = net.connect(port, '127.0.0.1', () => s.write('GET /other HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\n' +
      'Connection: Upgrade\r\nSec-WebSocket-Key: a\r\n\r\n'));
    let out = '';
    s.on('data', (d) => { out += d; });
    s.on('close', () => { assert.match(out, /400/); resolve(); });
  });

  // После всего этого мост жив и принимает нормальный телефон.
  const p = fakePhone(url, { code: CODE });
  await p.welcomed();
  assert.equal((await h.callTool('status')).isError, false);
  await h.close();
});

test('нагрузка: 3000 последовательных и 500 параллельных вызовов', async () => {
  const { h, url } = await hub();
  const p = fakePhone(url, { code: CODE });
  await p.welcomed();
  await h.callTool('select_window', { window_id: 1 });
  const t0 = performance.now();
  for (let i = 0; i < 3000; i++) {
    const r = await h.callTool('left_click', { coordinate: [i % 576, i % 1280] });
    assert.equal(r.isError, false);
  }
  const seq = performance.now() - t0;
  const t1 = performance.now();
  const rs = await Promise.all(Array.from({ length: 500 }, (_, i) => h.callTool('key', { text: `k${i}` })));
  const par = performance.now() - t1;
  assert.ok(rs.every((r, i) => !r.isError && txt(r) === `клавиша k${i}`), 'ответы не перепутаны');
  assert.equal(h.state.pending.size, 0);
  console.log(`  3000 последовательных: ${seq.toFixed(0)} мс (${(seq / 3000).toFixed(2)} мс/вызов); 500 параллельных: ${par.toFixed(0)} мс`);
  // Снимок ~1 МБ (как JPEG большого экрана) проходит целиком.
  p.ws.close();
  await waitFor(() => h.state.phone === null);
  const big = new WebSocket(url);
  big.addEventListener('open', () => big.send(JSON.stringify({ t: 'hello', proto: 1, code: CODE })));
  big.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data);
    if (m.t === 'call') big.send(JSON.stringify({ t: 'result', id: m.id, isError: false,
      content: [{ type: 'image', mimeType: 'image/jpeg', data: 'A'.repeat(1_400_000) }] }));
  });
  await waitFor(() => h.state.phone !== null);
  const shot = await h.callTool('screenshot');
  assert.equal(shot.content[0].data.length, 1_400_000);
  big.close();
  await h.close();
});

test('stdio: процесс моста отвечает на initialize и tools/list', async () => {
  const bin = fileURLToPath(new URL('../wdpilot-mobile.mjs', import.meta.url));
  const child = spawn(process.execPath, [bin, '--port', '0', '--host', '127.0.0.1', '--code', CODE], { stdio: ['pipe', 'pipe', 'pipe'] });
  const lines = [];
  let buf = '';
  child.stdout.on('data', (d) => {
    buf += d;
    let i;
    while ((i = buf.indexOf('\n')) >= 0) { lines.push(JSON.parse(buf.slice(0, i))); buf = buf.slice(i + 1); }
  });
  child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: {} }) + '\n');
  child.stdin.write('garbage\n');
  child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/list' }) + '\n');
  child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'status', arguments: {} } }) + '\n');
  await waitFor(() => lines.length >= 4, 5000);
  child.stdin.end();
  assert.equal(lines.find((l) => l.id === 1).result.serverInfo.name, 'wdpilot-mobile');
  assert.equal(lines.find((l) => l.error?.code === -32700).id, null);
  assert.equal(lines.find((l) => l.id === 2).result.tools.length, 22);
  assert.match(lines.find((l) => l.id === 3).result.content[0].text, /Телефон не подключён/);
  await new Promise((r) => child.on('exit', r));
});
