// Минимальный WebSocket-сервер (RFC 6455) без зависимостей: только текстовые сообщения,
// ping/pong, close, фрагментация. Для прототипа моста в локальной сети.
import { createHash } from 'node:crypto';
import { EventEmitter } from 'node:events';

const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

export class WsConn extends EventEmitter {
  constructor(socket, { maxMessage }) {
    super();
    this.socket = socket;
    this.maxMessage = maxMessage;
    this.buf = Buffer.alloc(0);
    this.parts = [];
    this.partsLen = 0;
    this.closed = false;
    socket.setNoDelay(true);
    socket.on('data', (d) => this.#onData(d));
    socket.on('close', () => this.#finish(1006, 'socket closed'));
    socket.on('error', () => this.#finish(1006, 'socket error'));
  }

  send(text) {
    if (this.closed) return false;
    this.socket.write(frame(0x1, Buffer.from(text, 'utf8')));
    return true;
  }

  ping() {
    if (!this.closed) this.socket.write(frame(0x9, Buffer.alloc(0)));
  }

  close(code = 1000, reason = '') {
    if (this.closed) return;
    const r = Buffer.from(reason, 'utf8').subarray(0, 120);
    const p = Buffer.alloc(2 + r.length);
    p.writeUInt16BE(code, 0);
    r.copy(p, 2);
    try { this.socket.end(frame(0x8, p)); } catch { /* сокет уже закрыт */ }
    this.#finish(code, reason);
  }

  #finish(code, reason) {
    if (this.closed) return;
    this.closed = true;
    this.socket.destroySoon?.();
    this.emit('close', code, reason);
  }

  #onData(d) {
    this.buf = this.buf.length ? Buffer.concat([this.buf, d]) : d;
    for (;;) {
      if (this.buf.length < 2) return;
      const b0 = this.buf[0], b1 = this.buf[1];
      const fin = (b0 & 0x80) !== 0, op = b0 & 0x0f, masked = (b1 & 0x80) !== 0;
      let len = b1 & 0x7f, off = 2;
      if (len === 126) { if (this.buf.length < 4) return; len = this.buf.readUInt16BE(2); off = 4; }
      else if (len === 127) {
        if (this.buf.length < 10) return;
        const hi = this.buf.readUInt32BE(2);
        if (hi !== 0) return this.close(1009, 'too big');
        len = this.buf.readUInt32BE(6); off = 10;
      }
      if (!masked) return this.close(1002, 'client frames must be masked');
      if (len > this.maxMessage) return this.close(1009, 'too big');
      if (this.buf.length < off + 4 + len) return;
      const mask = this.buf.subarray(off, off + 4);
      const payload = Buffer.from(this.buf.subarray(off + 4, off + 4 + len));
      for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
      this.buf = this.buf.subarray(off + 4 + len);

      if (op === 0x8) { this.close(1000, 'bye'); return; }
      if (op === 0x9) { this.socket.write(frame(0xA, payload)); continue; }
      if (op === 0xA) { this.emit('pong'); continue; }
      if (op === 0x2) return this.close(1003, 'binary not supported');
      if (op === 0x1 || op === 0x0) {
        if (op === 0x1 && this.parts.length) return this.close(1002, 'unexpected text frame');
        if (op === 0x0 && !this.parts.length) return this.close(1002, 'unexpected continuation');
        this.parts.push(payload);
        this.partsLen += payload.length;
        if (this.partsLen > this.maxMessage) return this.close(1009, 'too big');
        if (fin) {
          const msg = Buffer.concat(this.parts).toString('utf8');
          this.parts = []; this.partsLen = 0;
          this.emit('message', msg);
        }
        continue;
      }
      return this.close(1002, 'bad opcode');
    }
  }
}

function frame(op, payload) {
  const n = payload.length;
  let head;
  if (n < 126) { head = Buffer.alloc(2); head[1] = n; }
  else if (n < 65536) { head = Buffer.alloc(4); head[1] = 126; head.writeUInt16BE(n, 2); }
  else { head = Buffer.alloc(10); head[1] = 127; head.writeUInt32BE(0, 2); head.writeUInt32BE(n, 6); }
  head[0] = 0x80 | op;
  return Buffer.concat([head, payload]);
}

/** Привязать к http.Server: onConn(conn, req) для запросов на path. */
export function attachWs(server, path, onConn, { maxMessage = 8 * 1024 * 1024 } = {}) {
  server.on('upgrade', (req, socket) => {
    const url = new URL(req.url, 'http://x');
    const key = req.headers['sec-websocket-key'];
    if (url.pathname !== path || !key || (req.headers.upgrade || '').toLowerCase() !== 'websocket') {
      socket.end('HTTP/1.1 400 Bad Request\r\n\r\n');
      return;
    }
    const accept = createHash('sha1').update(key + GUID).digest('base64');
    socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n' +
      `Sec-WebSocket-Accept: ${accept}\r\n\r\n`);
    onConn(new WsConn(socket, { maxMessage }), req);
  });
}
