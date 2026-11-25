// Проверка телефона без Claude: сценарий по шагам через тот же мост.
// node examples/demo-driver.mjs [--app "Keep"]  — печатает адрес и код, ждёт телефон,
// выбирает приложение (на телефоне нужно нажать «Разрешить»), делает снимок в demo-shot.jpg,
// нажимает в центр, прокручивает вниз и снова снимает.
import { writeFileSync } from 'node:fs';
import { startHub, lanAddresses } from '../bridge/wdpilot-mobile.mjs';

const want = (process.argv[process.argv.indexOf('--app') + 1] || 'keep').toLowerCase();
const hub = startHub({ log: (s) => console.log('[мост]', s) });
const port = await hub.ready;
console.log(`На телефоне: адрес ${lanAddresses().map((a) => `ws://${a}:${port}/pilot`).join(' или ')}, код ${hub.state.code}`);
while (!hub.state.phone) await new Promise((r) => setTimeout(r, 500));

const call = async (name, args = {}) => {
  const r = await hub.callTool(name, args);
  const t = r.content.filter((c) => c.type === 'text').map((c) => c.text).join(' ');
  console.log(`${r.isError ? '✗' : '✓'} ${name} ${JSON.stringify(args)} → ${t.slice(0, 160)}`);
  const img = r.content.find((c) => c.type === 'image');
  if (img) writeFileSync(`demo-${name}-${Date.now()}.jpg`, Buffer.from(img.data, 'base64'));
  return r;
};

await call('status');
const apps = JSON.parse((await call('list_windows')).content[0].text);
const app = apps.find((a) => a.app.toLowerCase().includes(want) && a.allowed);
const bank = apps.find((a) => !a.allowed);
if (bank) await call('select_window', { window_id: bank.window_id }); // должен быть отказ
if (!app) { console.log(`приложение «${want}» не найдено`); process.exit(1); }
console.log('Нажмите «Разрешить» на телефоне…');
if ((await call('select_window', { window_id: app.window_id })).isError) process.exit(1);
await call('screenshot');
await call('type', { text: '4111 1111 1111 1111' }); // должен быть отказ
await call('scroll', { scroll_direction: 'down', scroll_amount: 3 });
await call('screenshot');
await call('key', { text: 'Back' });
await call('release_window');
await hub.close();
