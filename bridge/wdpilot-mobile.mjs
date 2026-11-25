#!/usr/bin/env node
// WorkDesk AI Pilot для телефона — мост на ПК.
// Claude Code (или свой агент) ──MCP stdio──► этот мост ──WebSocket──► исполнитель на Android.
// Прототип: телефон подключается к мосту в локальной сети по одноразовому коду.
// В продукте вместо прямого подключения — релей workdesk.kz (тот же протокол, см. docs/PROTOCOL.md).
import http from 'node:http';
import os from 'node:os';
import { randomInt } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { attachWs } from './ws.mjs';
import { INSTRUCTIONS, TOOLS, TOOL_NAMES, timeoutFor } from './tools.mjs';

export const VERSION = '0.1.0';
export const PROTO = 1;
const MAX_BAD_CODES = 5;

const text = (t, isError = false) => ({ content: [{ type: 'text', text: t }], isError });

export function lanAddresses() {
  return Object.values(os.networkInterfaces()).flat()
    .filter((a) => a && a.family === 'IPv4' && !a.internal).map((a) => a.address);
}

/** Сервер для телефона + вызовы инструментов. Используется и MCP-слоем, и тестами. */
export function startHub({ port = 8765, host = '0.0.0.0', code, log = () => {}, timeoutScale = 1, maxMessage } = {}) {
  const st = {
    code: code || String(randomInt(0, 1_000_000)).padStart(6, '0'),
    fixedCode: !!code,
    badCodes: 0,
    phone: null, // { conn, info }
    nextId: 1,
    pending: new Map(),
    stoppedAt: null,
  };

  const server = http.createServer((req, res) => {
    res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('WorkDesk AI Pilot bridge\n');
  });

  const failPending = (why) => {
    for (const [id, p] of st.pending) { clearTimeout(p.timer); p.resolve(text(why, true)); st.pending.delete(id); }
  };

  const tmo = (name, args) => Math.max(10, Math.round(timeoutFor(name, args) * timeoutScale));

  attachWs(server, '/pilot', (conn, req) => {
    let authed = false;
    const helloTimer = setTimeout(() => !authed && conn.close(4408, 'no hello'), 10_000);
    conn.on('message', (raw) => {
      let m;
      try { m = JSON.parse(raw); } catch { return conn.close(4400, 'bad json'); }
      if (!m || typeof m !== 'object' || typeof m.t !== 'string') return conn.close(4400, 'bad message');

      if (!authed) {
        if (m.t !== 'hello') return conn.close(4400, 'hello expected');
        if (m.proto !== PROTO) return conn.close(4426, `proto ${PROTO} expected`);
        if (String(m.code ?? '') !== st.code) {
          st.badCodes++;
          log(`неверный код с ${req.socket.remoteAddress} (${st.badCodes}/${MAX_BAD_CODES})`);
          if (st.badCodes >= MAX_BAD_CODES && !st.fixedCode) {
            st.code = String(randomInt(0, 1_000_000)).padStart(6, '0');
            st.badCodes = 0;
            log(`слишком много неверных кодов — новый код: ${st.code}`);
          }
          return conn.close(4401, 'bad code');
        }
        clearTimeout(helloTimer);
        authed = true;
        if (st.phone) { failPending('телефон переподключился, повторите действие'); st.phone.conn.close(4001, 'replaced'); }
        st.phone = { conn, info: { device: String(m.device || '?'), android: m.android ?? null, app: String(m.app || '?') } };
        st.stoppedAt = null;
        conn.send(JSON.stringify({ t: 'welcome', proto: PROTO, bridge: VERSION }));
        log(`телефон подключён: ${st.phone.info.device}, Android API ${st.phone.info.android}`);
        return;
      }

      if (m.t === 'result') {
        const p = st.pending.get(m.id);
        if (!p) return; // опоздавший ответ после таймаута
        clearTimeout(p.timer);
        st.pending.delete(m.id);
        const content = Array.isArray(m.content) ? m.content : [{ type: 'text', text: 'пустой ответ телефона' }];
        p.resolve({ content, isError: !!m.isError });
      } else if (m.t === 'stopped') {
        st.stoppedAt = new Date().toISOString();
        log('человек нажал «Стоп» на телефоне');
      }
    });
    conn.on('close', () => {
      clearTimeout(helloTimer);
      if (st.phone?.conn === conn) {
        st.phone = null;
        failPending('телефон отключился. Попросите человека открыть WorkDesk AI Pilot на телефоне.');
        log('телефон отключился');
      }
    });
  }, maxMessage ? { maxMessage } : undefined);

  const pingTimer = setInterval(() => st.phone?.conn.ping(), 25_000);

  const ready = new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => resolve(server.address().port));
  });

  function statusText() {
    if (!st.phone) {
      const addrs = lanAddresses();
      const p = server.address()?.port ?? port;
      return `Телефон не подключён. Попросите человека открыть на телефоне WorkDesk AI Pilot и ввести:\n` +
        `адрес: ${addrs.map((a) => `ws://${a}:${p}/pilot`).join(' или ') || `ws://<IP этого ПК>:${p}/pilot`}\n` +
        `код: ${st.code}\nТелефон и ПК должны быть в одной сети.`;
    }
    return null;
  }

  function callTool(name, args = {}) {
    if (!TOOL_NAMES.has(name)) return Promise.resolve(text(`неизвестный инструмент: ${name}`, true));
    if (!st.phone) {
      const s = statusText();
      return Promise.resolve(name === 'status' ? text(s) : text(s, true));
    }
    const id = st.nextId++;
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        st.pending.delete(id);
        resolve(text(`телефон не ответил за ${Math.round(tmo(name, args) / 1000)} с`, true));
      }, tmo(name, args));
      st.pending.set(id, { resolve, timer });
      st.phone.conn.send(JSON.stringify({ t: 'call', id, name, args }));
    });
  }

  async function close() {
    clearInterval(pingTimer);
    failPending('мост остановлен');
    st.phone?.conn.close(1001, 'bridge stopped');
    await new Promise((r) => server.close(() => r()));
  }

  return { ready, callTool, close, state: st };
}

/** MCP (JSON-RPC 2.0 построчно в stdio). */
export function mcpHandler(hub) {
  return async (msg) => {
    const { id, method, params } = msg;
    const reply = (result) => ({ jsonrpc: '2.0', id, result });
    if (id === undefined || id === null) return null; // уведомления
    switch (method) {
      case 'initialize':
        return reply({
          protocolVersion: params?.protocolVersion || '2025-06-18',
          capabilities: { tools: {} },
          serverInfo: { name: 'wdpilot-mobile', version: VERSION },
          instructions: INSTRUCTIONS,
        });
      case 'ping': return reply({});
      case 'tools/list': return reply({ tools: TOOLS });
      case 'tools/call': return reply(await hub.callTool(params?.name, params?.arguments || {}));
      default: return { jsonrpc: '2.0', id, error: { code: -32601, message: `метод не найден: ${method}` } };
    }
  };
}

function parseArgs(argv) {
  const o = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--port') o.port = Number(argv[++i]);
    else if (a === '--host') o.host = argv[++i];
    else if (a === '--code') o.code = argv[++i];
    else if (a === '--version') o.version = true;
  }
  return o;
}

async function main() {
  const o = parseArgs(process.argv.slice(2));
  if (o.version) { console.log(VERSION); return; }
  const log = (s) => process.stderr.write(`[wdpilot-mobile] ${s}\n`);
  const hub = startHub({ port: o.port ?? Number(process.env.WDPILOT_MOBILE_PORT || 8765), host: o.host, code: o.code, log });
  const port = await hub.ready;
  log(`жду телефон на порту ${port}, код ${hub.state.code}`);
  const handle = mcpHandler(hub);

  let buf = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (chunk) => {
    buf += chunk;
    let nl;
    while ((nl = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      if (!line) continue;
      let msg;
      try { msg = JSON.parse(line); } catch {
        process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'parse error' } }) + '\n');
        continue;
      }
      handle(msg).then((r) => r && process.stdout.write(JSON.stringify(r) + '\n'))
        .catch((e) => process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: msg.id ?? null,
          error: { code: -32603, message: String(e?.message || e) } }) + '\n'));
    }
  });
  process.stdin.on('end', async () => { await hub.close(); process.exit(0); });
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) main();
