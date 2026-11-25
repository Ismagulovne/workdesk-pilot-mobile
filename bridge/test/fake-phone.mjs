// Имитация исполнителя на телефоне для тестов моста (протокол, а не Android).
// Экран — приложение «Заметки» 1080×2400 с кнопкой «+» и полем ввода.
export function fakePhone(url, { code, device = 'Test Phone', allow = true, ignore = new Set(), delayMs = 0 } = {}) {
  const ws = new WebSocket(url);
  const st = { selected: null, stopped: false, text: '', lastTap: null, closed: null, welcomed: false };
  const apps = [
    { window_id: 1, app: 'Заметки', package: 'com.example.notes', allowed: true },
    { window_id: 2, app: 'Kaspi.kz', package: 'kz.kaspi.mobile', allowed: false },
  ];
  const ok = (t) => ({ content: [{ type: 'text', text: t }], isError: false });
  const err = (t) => ({ content: [{ type: 'text', text: t }], isError: true });

  function exec(name, a) {
    if (st.stopped && !['status', 'list_windows', 'select_window'].includes(name)) {
      return err('Остановлено пользователем (кнопка «Стоп» на телефоне).');
    }
    switch (name) {
      case 'status': return ok(JSON.stringify({ device, selected: st.selected?.app ?? null, stopped: st.stopped }));
      case 'list_windows': return ok(JSON.stringify(apps));
      case 'select_window': {
        const app = apps.find((x) => x.window_id === a.window_id);
        if (!app) return err('нет такого приложения');
        if (!app.allowed) return err('приложение запрещено правилами');
        if (!allow) return err('человек отказал');
        st.selected = app; st.stopped = false;
        return ok(`разрешено: ${app.app}`);
      }
      case 'screenshot':
        if (!st.selected) return err('сначала select_window');
        return { content: [{ type: 'image', data: Buffer.from('fakejpeg').toString('base64'), mimeType: 'image/jpeg' },
          { type: 'text', text: 'снимок 576×1280 (экран 1080×2400)' }], isError: false };
      case 'left_click': {
        const [x, y] = a.coordinate || [];
        if (!(x >= 0 && y >= 0 && x < 576 && y < 1280)) return err('точка вне снимка');
        st.lastTap = [x, y];
        return ok('нажато');
      }
      case 'type':
        if (/\b(?:\d[ -]?){13,19}\b/.test(a.text || '')) return err('ввод запрещён: номер карты');
        st.text += a.text; return ok(`введено символов: ${[...(a.text || '')].length}`);
      case 'key': return ok(`клавиша ${a.text}`);
      case 'wait': return ok('подождали');
      default: return err(`${name}: не реализовано в имитации`);
    }
  }

  ws.addEventListener('open', () => ws.send(JSON.stringify({ t: 'hello', proto: 1, code, device, android: 35, app: 'fake' })));
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data);
    if (m.t === 'welcome') { st.welcomed = true; return; }
    if (m.t !== 'call' || ignore.has(m.name)) return;
    const res = exec(m.name, m.args || {});
    const send = () => ws.readyState === 1 && ws.send(JSON.stringify({ t: 'result', id: m.id, ...res }));
    delayMs ? setTimeout(send, delayMs) : send();
  });
  ws.addEventListener('close', (ev) => { st.closed = ev.code; });

  return {
    ws, st,
    pressStop() { st.stopped = true; st.selected = null; ws.send(JSON.stringify({ t: 'stopped' })); },
    welcomed: () => waitFor(() => st.welcomed),
    closedWith: () => waitFor(() => st.closed !== null).then(() => st.closed),
  };
}

export async function waitFor(cond, ms = 3000) {
  const t0 = Date.now();
  while (!cond()) {
    if (Date.now() - t0 > ms) throw new Error('timeout waiting');
    await new Promise((r) => setTimeout(r, 10));
  }
}
