// Сквозной прогон исполнителя на эмуляторе (или телефоне по adb) через настоящий мост.
// Запуск: node tools/e2e/run.mjs --device "Samsung Galaxy S24" --out e2e-out
// Нужны: adb в PATH, установленные APK исполнителя и «Тест-заметок», эмулятор видит ПК как 10.0.2.2.
// Человека изображает этот скрипт: нажимает «Согласен», «Разрешить», «Да, один раз» / «Отказать», «СТОП».
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { startHub } from '../../bridge/wdpilot-mobile.mjs';

const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const DEVICE = arg('--device', 'emulator');
const OUT = arg('--out', 'e2e-out');
const HOST_IP = arg('--host-ip', '10.0.2.2');
const PILOT = 'kz.workdesk.ai.pilot';
const NOTES = 'kz.workdesk.ai.pilot.testnotes';
const CODE = '246810';
mkdirSync(OUT, { recursive: true });
process.on('unhandledRejection', (e) => { check('прогон без исключений', false, e?.stack || e); finish(); });
process.on('uncaughtException', (e) => { check('прогон без исключений', false, e?.stack || e); finish(); });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const adb = (...a) => execFileSync('adb', a, { encoding: 'utf8', maxBuffer: 64 << 20 }).trim();
const sh = (cmd) => adb('shell', cmd);
const results = [];
let failed = 0;
let hub = null;
const t0 = Date.now();

function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail: String(detail).slice(0, 300) });
  if (!ok) failed++;
  console.log(`${ok ? '✓' : '✗'} ${name}${detail ? ' — ' + String(detail).slice(0, 200) : ''}`);
  return !!ok;
}

function shot(name) {
  try {
    const png = execFileSync('adb', ['exec-out', 'screencap', '-p'], { maxBuffer: 64 << 20 });
    writeFileSync(`${OUT}/${name}.png`, png);
  } catch (e) { console.log('снимок не удался', e.message); }
}

/** Последний полный отчёт по каждому окну: Map(pkg|class → { pkg, focus, id, nodes[] }). */
function reports() {
  const raw = execFileSync('adb', ['logcat', '-d', '-v', 'raw', '-s', 'E2E:I'], { encoding: 'utf8', maxBuffer: 64 << 20 });
  const parts = new Map();
  for (const line of raw.split(/\r?\n/)) {
    const m = line.match(/^([\w.]+)\|([\w.]+)\|([01])\|(\d+)\|(\d+)\|(\d+)\|(\[.*\])\s*$/);
    if (!m) continue;
    const key = `${m[1]}|${m[2]}|${m[4]}`;
    if (!parts.has(key)) parts.set(key, { win: `${m[1]}|${m[2]}`, pkg: m[1], focus: m[3] === '1', id: +m[4], n: +m[6], chunks: [] });
    try { parts.get(key).chunks[+m[5]] = JSON.parse(m[7]); } catch { /* обрезанная строка — отчёт неполный */ }
  }
  const last = new Map();
  for (const p of parts.values()) {
    if (p.chunks.filter(Boolean).length !== p.n) continue;
    const prev = last.get(p.win);
    if (!prev || prev.id < p.id) last.set(p.win, { ...p, nodes: p.chunks.flat() });
  }
  return last;
}

const seen = new Map();
const pause = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);

/**
 * Что сейчас видит «человек»: отчёты датчика E2E (отладочная сборка) по окнам с фокусом.
 * Ждёт отчёт новее предыдущего вызова (датчик пишет каждые 400 мс), чтобы не читать устаревший экран.
 * uiautomator не используем: он приостанавливает службы специальных возможностей, то есть и исполнитель.
 */
function dump() {
  let last = reports();
  for (let i = 0; i < 12; i++) {
    const stale = [...last.values()].some((r) => r.focus && (seen.get(r.win) ?? -1) >= r.id);
    if (!stale) break;
    pause(200);
    last = reports();
  }
  const out = [];
  for (const rep of last.values()) {
    seen.set(rep.win, rep.id);
    if (!rep.focus) continue;
    for (const n of rep.nodes) {
      out.push({ text: n.t, hint: n.h || '', cls: n.e ? 'android.widget.EditText' : 'android.widget.TextView', pkg: rep.pkg,
        focused: !!n.f, x1: n.x1, y1: n.y1, x2: n.x2, y2: n.y2, cx: (n.x1 + n.x2) >> 1, cy: (n.y1 + n.y2) >> 1 });
    }
  }
  return out;
}

function swipe(down) {
  const size = sh('wm size').match(/(\d+)x(\d+)\s*$/);
  const w = size ? +size[1] : 1080; const h = size ? +size[2] : 2340;
  const [a, b] = down ? [0.3, 0.8] : [0.75, 0.3];
  sh(`input swipe ${w >> 1} ${Math.round(h * a)} ${w >> 1} ${Math.round(h * b)} 300`);
}
async function scrollTop() { for (let i = 0; i < 5; i++) { swipe(true); await sleep(300); } await sleep(500); }

async function waitNode(pred, ms = 20000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const n = dump().find(pred);
    if (n) return n;
    await sleep(500);
  }
  return null;
}
const tap = (n) => sh(`input tap ${n.cx} ${n.cy}`);
const byText = (t) => (n) => n.text === t;

async function scrollTo(pred, tries = 8) {
  for (let i = 0; i < tries; i++) {
    const n = dump().find(pred);
    if (n) return n;
    const size = sh('wm size').match(/(\d+)x(\d+)\s*$/);
    const w = size ? +size[1] : 1080; const h = size ? +size[2] : 2340;
    sh(`input swipe ${w >> 1} ${Math.round(h * 0.75)} ${w >> 1} ${Math.round(h * 0.3)} 300`);
    await sleep(600);
  }
  return null;
}

/** Ответ «человека» на окно исполнителя, пока идёт вызов инструмента. */
async function answering(promise, button) {
  let done = false;
  let tapped = false;
  promise.finally(() => { done = true; });
  while (!done) {
    const names = [].concat(button);
    const n = dump().find((x) => x.pkg === PILOT && names.includes(x.text));
    if (n && !tapped) { await sleep(300); tap(n); tapped = true; }
    await sleep(400);
  }
  return { r: await promise, tapped };
}

const textOf = (r) => r.content.filter((c) => c.type === 'text').map((c) => c.text).join(' ');

/** Клавиатура после ввода закрывает строки списка и сбивает датчик E2E; человек бы её тоже убрал. */
function hideKeyboard() {
  try { if (/mInputShown=true/.test(sh('dumpsys input_method'))) { sh('input keyevent 111'); pause(600); } } catch { /* нет сведений — не трогаем */ }
}

function waitBroadcastIdle() {
  try { execFileSync('adb', ['shell', 'am', 'wait-for-broadcast-idle'], { timeout: 90000, stdio: 'ignore' }); } catch { /* старый Android или тайм-аут */ }
}
const serviceBound = () => /Bound services:\{[^}]*WorkDesk AI Pilot/.test(sh('dumpsys accessibility'));

// ---------- подготовка телефона ----------
const info = {
  device: DEVICE,
  android: sh('getprop ro.build.version.release'),
  sdk: sh('getprop ro.build.version.sdk'),
  size: sh('wm size').split('\n').pop(),
  density: sh('wm density').split('\n').pop(),
};
console.log('Устройство:', JSON.stringify(info));
try { sh(`pm grant ${PILOT} android.permission.POST_NOTIFICATIONS`); } catch { /* Android 11–12 */ }
sh(`am force-stop ${NOTES}`);
sh(`pm clear ${PILOT}`);
try { sh(`pm grant ${PILOT} android.permission.POST_NOTIFICATIONS`); } catch { /* Android 11–12 */ }
// pm clear = принудительная остановка. На медленном эмуляторе система обрабатывает её с опозданием до 30 с
// и тогда выключает уже включённую службу посреди прогона (Redmi Note 13, 25.11). Ждём, пока очередь опустеет.
waitBroadcastIdle();
adb('logcat', '-c');
sh(`am start -W -n ${PILOT}/.MainActivity`);
await waitNode(byText('Прежде чем начать'), 45000);
const agree = await scrollTo(byText('Согласен, продолжить'));
check('раскрытие информации показано до согласия', agree);
shot('01-disclosure');
if (agree) tap(agree);
sh(`settings put secure enabled_accessibility_services ${PILOT}/${PILOT}.PilotService`);
sh('settings put secure accessibility_enabled 1');
let linkHead = null;
for (let i = 0; i < 20 && !linkHead; i++) {
  sh(`settings put secure enabled_accessibility_services ${PILOT}/${PILOT}.PilotService`);
  sh('settings put secure accessibility_enabled 1');
  sh('input keyevent 3'); await sleep(800); // домой и обратно — экран перерисуется в onResume
  sh(`am start -W -n ${PILOT}/.MainActivity`);
  linkHead = await waitNode(byText('2. Подключение к ПК'), 3000);
}
check('служба специальных возможностей включилась', linkHead,
  sh('dumpsys accessibility').split(/\r?\n/).filter((l) => /pilot|Bound services|Enabled services/i.test(l)).join(' | ').slice(0, 280));
shot('02-main');

// ---------- список запретов (вводит человек) ----------
const saveBtn = await scrollTo(byText('Сохранить список'));
if (saveBtn) {
  const box = dump().filter((n) => n.pkg === PILOT && n.cls === 'android.widget.EditText' && n.y2 <= saveBtn.y1).pop();
  if (box) { tap(box); await sleep(300); sh('input text FRK_ROZNICA_PROD'); sh('input keyevent 4'); await sleep(300); }
  const again = dump().find(byText('Сохранить список'));
  if (again) tap(again);
}
check('список запретов заполнен человеком', saveBtn);

// ---------- подключение к мосту ----------
waitBroadcastIdle();
if (!serviceBound()) {
  console.log('служба выключилась до подключения — включаем снова');
  sh(`settings put secure enabled_accessibility_services ${PILOT}/${PILOT}.PilotService`);
  sh('settings put secure accessibility_enabled 1');
  for (let i = 0; i < 20 && !serviceBound(); i++) await sleep(500);
}
hub = startHub({ port: 8765, host: '0.0.0.0', code: CODE, log: (s) => console.log('[мост]', s) });
await hub.ready;
sh('input keyevent 3'); await sleep(500);
sh(`am start -W -n ${PILOT}/.MainActivity`);
await sleep(800);
await scrollTop();
const connectBtn = await scrollTo(byText('Подключить'));
const inputs = dump().filter((n) => n.pkg === PILOT && n.cls === 'android.widget.EditText' && connectBtn && n.y2 <= connectBtn.y1);
if (inputs.length >= 2) {
  tap(inputs[0]); await sleep(300); sh(`input text ws://${HOST_IP}:8765/pilot`);
  tap(inputs[1]); await sleep(300); sh(`input text ${CODE}`);
  sh('input keyevent 4'); await sleep(400);
  const b = dump().find(byText('Подключить'));
  if (b) tap(b);
}
for (let i = 0; i < 90 && !hub.state.phone; i++) await sleep(500);
check('телефон подключился к мосту по коду', hub.state.phone, hub.state.phone ? JSON.stringify(hub.state.phone.info) : `кнопка ${!!connectBtn}, поля ${inputs.length}, состояние: ${(dump().find((n) => n.text.startsWith('Состояние')) || {}).text}`);
if (!hub.state.phone) finish();

const call = (name, args = {}) => hub.callTool(name, args);

// ---------- инструменты ----------
let r = await call('status');
check('status', !r.isError, textOf(r));

r = await call('list_windows');
const apps = JSON.parse(textOf(r));
const notesApp = apps.find((a) => a.package === NOTES);
const settingsApp = apps.find((a) => a.package === 'com.android.settings');
check('list_windows: «Тест-заметки» разрешены', notesApp && notesApp.allowed);
check('list_windows: «Настройки» запрещены', settingsApp && !settingsApp.allowed);
if (settingsApp) {
  r = await call('select_window', { window_id: settingsApp.window_id });
  check('select_window «Настройки» → отказ без окна', r.isError, textOf(r));
}

sh(`am start -W -n ${NOTES}/.NotesActivity`);
const sel = answering(call('select_window', { window_id: notesApp.window_id }), ['Разрешить', 'Allow', 'Рұқсат беру']);
await sleep(1500);
shot('03-consent');
let { r: rs, tapped } = await sel;
check('окно «Разрешить ИИ?» показано и человек разрешил', tapped && !rs.isError, textOf(rs));
await sleep(2500);
shot('04-overlay');

r = await call('screenshot');
const note = textOf(r);
const m = note.match(/(\d+)×(\d+) \(экран (\d+)×(\d+)\)/);
check('screenshot только выбранного приложения', !r.isError && m && r.content.some((c) => c.type === 'image'), note);
if (r.content.find((c) => c.type === 'image')) writeFileSync(`${OUT}/05-agent-view.jpg`, Buffer.from(r.content.find((c) => c.type === 'image').data, 'base64'));
const scale = m ? +m[1] / +m[3] : 1;
const toShot = (n) => [Math.round(n.cx * scale), Math.round(n.cy * scale)];

let field = await waitNode((n) => n.pkg === NOTES && n.cls === 'android.widget.EditText', 20000);
if (!field) { check('«Тест-заметки» на экране после разрешения', false); finish(); }
r = await call('left_click', { coordinate: toShot(field) });
field = await waitNode((n) => n.pkg === NOTES && n.cls === 'android.widget.EditText' && n.focused, 6000)
  || dump().find((n) => n.pkg === NOTES && n.cls === 'android.widget.EditText');
check('касание по координатам снимка попало в поле', !r.isError && field && field.focused, textOf(r));

r = await call('type', { text: 'Купить молоко' });
field = await waitNode((n) => n.pkg === NOTES && n.cls === 'android.widget.EditText' && n.text === 'Купить молоко', 6000)
  || dump().find((n) => n.pkg === NOTES && n.cls === 'android.widget.EditText');
check('ввод текста по-русски', !r.isError && field && field.text === 'Купить молоко', field && field.text);

r = await call('type', { text: '4111 1111 1111 1111' });
check('номер карты не вводится', r.isError, textOf(r));

// Enter, пока на экране видна цель из списка запретов, — только после «Да» человека.
let enter = await answering(call('key', { text: 'Return' }), ['Да, один раз', 'Yes, once', 'Иә, бір рет']);
await sleep(600);
check('Enter при видимой запрещённой цели спросил человека', enter.tapped, textOf(enter.r));
check('Enter после «Да» сохранил заметку', !enter.r.isError && !!(await waitNode(byText('Заметок: 1'), 6000)), textOf(enter.r));
shot('06-note-saved');

r = await call('screenshot');
const prod = await waitNode(byText('FRK_ROZNICA_PROD'), 10000);
if (!prod) { check('строка FRK_ROZNICA_PROD видна', false); finish(); }
const deny = answering(call('left_click', { coordinate: toShot(prod) }), ['Отказать', 'Deny', 'Бас тарту']);
await sleep(1500);
shot('07-blocked-confirm');
const dn = await deny;
await sleep(500);
check('касание запрещённой цели без «Да» → отказ «спросите пользователя»',
  dn.tapped && dn.r.isError && /Спросите пользователя/.test(textOf(dn.r)) && !dump().find(byText('Открыта база: FRK_ROZNICA_PROD')), textOf(dn.r));

hideKeyboard();
r = await call('screenshot');
const testMain = await waitNode(byText('TEST_MAIN'), 10000);
if (!testMain) { check('строка TEST_MAIN видна', false); finish(); }
r = await call('left_click', { coordinate: toShot(testMain) });
await sleep(500);
check('касание обычной строки работает', !r.isError && !!(await waitNode(byText('Открыта база: TEST_MAIN'), 6000)), textOf(r));

hideKeyboard();
const before = (await waitNode((n) => n.pkg === NOTES && /^Строка \d+$/.test(n.text), 6000)) || undefined;
r = await call('scroll', { coordinate: [200, 400], scroll_direction: 'down', scroll_amount: 5 });
await sleep(1200);
const after = before && dump().find((n) => n.pkg === NOTES && n.text === before.text);
check('прокрутка', !r.isError && before && (!after || after.y1 < before.y1 - 50),
  `${textOf(r)}; «${before?.text}» было y=${before?.y1}, стало ${after ? 'y=' + after.y1 : 'за краем'}`);

r = await call('zoom', { region: [0, 0, 300, 300] });
check('zoom', !r.isError && r.content.some((c) => c.type === 'image'), textOf(r));

r = await call('middle_click', { coordinate: [10, 10] });
check('неподдерживаемое действие — понятный отказ', r.isError, textOf(r));

// ---------- нагрузка: снимки подряд ----------
const N = 30;
const times = [];
for (let i = 0; i < N; i++) {
  const s = Date.now();
  const q = await call('screenshot');
  if (q.isError) break;
  times.push(Date.now() - s);
}
const avg = times.length ? Math.round(times.reduce((a, b) => a + b, 0) / times.length) : 0;
const p95 = times.length ? [...times].sort((a, b) => a - b)[Math.floor(times.length * 0.95) - 1] : 0;
check(`нагрузка: ${N} снимков подряд`, times.length === N, `среднее ${avg} мс, p95 ${p95} мс`);

// ---------- СТОП человеком ----------
sh('input keyevent 3'); await sleep(500);
sh(`am start -W -n ${PILOT}/.MainActivity`);
await scrollTop();
const stopBtn = await scrollTo(byText('СТОП — прекратить управление'));
if (stopBtn) tap(stopBtn);
for (let i = 0; i < 20 && !hub.state.stoppedAt; i++) await sleep(300);
check('СТОП: мост получил «остановлено»', hub.state.stoppedAt);
sh(`am start -W -n ${NOTES}/.NotesActivity`);
r = await call('screenshot');
check('после СТОП действия запрещены', r.isError, textOf(r));
shot('08-after-stop');

finish();

function finish() {
  const summary = { ...info, seconds: Math.round((Date.now() - t0) / 1000), passed: results.length - failed, failed, results };
  writeFileSync(`${OUT}/result.json`, JSON.stringify(summary, null, 2));
  console.log(`Итог: ${summary.passed} из ${results.length}`);
  hub?.close?.();
  process.exit(failed ? 1 : 0);
}
