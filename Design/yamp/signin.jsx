// signin.jsx — 9 · Sources and Jellyfin sign-in (Phase 4) + 13 · List states, unavailable sources and the
// music that is available offline (Settings → Storage).
// Exposes signinSections(): the canvas sections of this file, each
// { id, title, subtitle, boards: [{ id, label, w, h, el }] } — wired into YAMP.html's buildSections().
// Every top-level name here starts with `Si` (the canvas scripts share one global scope).

const SI_SERVER = 'https://music.example.org';

// ── Local helpers ────────────────────────────────────────────────────────────
// Settings card: one rounded plate per group, like the Settings sheet as built.
function SiCard({ children, style }) {
  return <div style={{ borderRadius: 24, background: HF.glassFill, border: `1px solid ${HF.hairline}`, padding: '0 14px', ...style }}>{children}</div>;
}
// The Hi-Fi activity mark: a thin white arc on a faint ring (HiFiActivityIndicator).
function SiSpinner({ size = 22 }) {
  const s = Math.max(1.5, size / 11), r = size / 2 - s / 2, c = size / 2, a = (100 * Math.PI) / 180;
  return (
    <svg width={size} height={size} style={{ flex: '0 0 auto' }}>
      <circle cx={c} cy={c} r={r} fill="none" stroke={HF.glassBorder} strokeWidth={s} />
      <path d={`M ${c + r} ${c} A ${r} ${r} 0 0 1 ${c + r * Math.cos(a)} ${c + r * Math.sin(a)}`} fill="none" stroke={HF.text} strokeWidth={s} strokeLinecap="round" />
    </svg>
  );
}
// Field with what the shared one lacks: hint line, focus rim, trailing icon, car size.
function SiField({ label, value, placeholder, icon, error, note, secure, trailing, focused, big, style }) {
  const rim = error ? HF.danger : focused ? HF.steelEdge : HF.glassBorder;
  return (
    <div style={{ minWidth: 0, ...style }}>
      <div className="hf-ellipsis" style={{ fontSize: big ? 16 : 13, lineHeight: big ? '22px' : '17px', fontWeight: 600, color: HF.text2, marginBottom: 6 }}>{label}</div>
      <div style={{ height: big ? 64 : 52, borderRadius: big ? 18 : 14, padding: big ? '0 18px' : '0 14px', display: 'flex', alignItems: 'center', gap: big ? 12 : 10,
        background: 'rgba(0,0,0,0.35)', border: `1px solid ${rim}`, boxShadow: 'inset 0 2px 6px rgba(0,0,0,0.45)' }}>
        {icon && <Icon name={icon} size={big ? 24 : 20} color={HF.text3} />}
        <div style={{ flex: 1, minWidth: 0, display: 'flex', alignItems: 'center', gap: 2 }}>
          <div className="hf-ellipsis" style={{ fontSize: big ? 20 : 16, color: value ? HF.text : HF.text3, letterSpacing: secure && value ? '0.2em' : 0 }}>
            {value ? (secure ? '••••••••••' : value) : (focused ? '' : placeholder)}
          </div>
          {focused && <div style={{ width: 2, height: big ? 26 : 22, borderRadius: 1, background: HF.accent, flex: '0 0 auto' }} />}
        </div>
        {trailing}
      </div>
      {note && <div style={{ fontSize: big ? 16 : 13, lineHeight: big ? '22px' : '17px', color: error ? HF.danger : HF.text3, marginTop: 6 }}>{note}</div>}
    </div>
  );
}
// One music source: provider plaque, name, state line, trailing control.
// `handle` = the card can be dragged to another place in the list (D30: the order of sources matters).
function SiSourceRow({ provider, title, sub, ok, trailing, big, handle }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: big ? 18 : 14, height: big ? 88 : 72 }}>
      {handle && <Icon name="drag_indicator" size={big ? 32 : 24} color={HF.text3} style={{ marginRight: -8 }} />}
      <ProviderBadge provider={provider} size={big ? 52 : 40} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="hf-ellipsis" style={{ fontSize: big ? 22 : 17, lineHeight: big ? '28px' : '22px', fontWeight: big ? 700 : 600 }}>{title}</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 5, marginTop: 1 }}>
          {ok && <Icon name="check_circle" size={big ? 18 : 15} color={HF.success} />}
          <div className="hf-ellipsis" style={{ fontSize: big ? 16 : 13, lineHeight: big ? '22px' : '17px', color: HF.text2 }}>{sub}</div>
        </div>
      </div>
      {trailing}
    </div>
  );
}
// The one message plate of the app (HiFiSnackbar).
function SiSnackbar({ children, bottom = 24 }) {
  return (
    <div style={{ position: 'absolute', left: 16, right: 16, bottom, borderRadius: 18, padding: '14px 18px', background: HF.dialog,
      border: `1px solid ${HF.glassBorder}`, boxShadow: '0 12px 30px rgba(0,0,0,0.55)', ...HFType.body }}>{children}</div>
  );
}
// Empty / failed content (HiFiEmptyState): icon plaque, short title, one line, at most one metal action.
function SiState({ icon, title, message, action, actionIcon, big, style }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', textAlign: 'center', padding: big ? 0 : '40px 32px', ...style }}>
      <IconPlaque icon={icon} size={big ? 96 : 56} style={big ? { borderRadius: 24 } : undefined} />
      <div style={{ fontSize: big ? 32 : 17, lineHeight: big ? '38px' : '22px', fontWeight: 700, marginTop: big ? 24 : 18 }}>{title}</div>
      {message && <div style={{ fontSize: big ? 20 : 13, lineHeight: big ? '27px' : '17px', color: HF.text2, marginTop: big ? 10 : 6, maxWidth: big ? 700 : 320, textWrap: 'balance' }}>{message}</div>}
      {action && (
        <MetalPill height={big ? 76 : 44} style={{ marginTop: big ? 28 : 20, fontSize: big ? 22 : 15, padding: big ? '0 64px' : '0 24px' }}>
          {actionIcon && <Icon name={actionIcon} size={big ? 32 : 22} color={HF.onMetal} />}{action}
        </MetalPill>
      )}
    </div>
  );
}
// Placeholders in the shape of rows while a list loads (HiFiSkeletonRows; no pulse in lite mode).
function SiSkeletonRows({ count = 6 }) {
  const bar = (w, h) => <div style={{ width: w, height: h, borderRadius: h / 2, background: HF.glassFill }} />;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      {Array.from({ length: count }, (_, i) => (
        <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          <div style={{ width: 52, height: 52, borderRadius: HFShape.cover, background: HF.glassFill }} />
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>{bar(170 - (i % 3) * 30, 12)}{bar(110 - (i % 2) * 20, 10)}</div>
        </div>
      ))}
    </div>
  );
}
// The two ways to sign in to Jellyfin, offered after the server address (D31).
function SiWays({ value = 'pw', big, style }) {
  const t = useT();
  const opts = [['qc', t('Quick Connect', 'Быстрое подключение')], ['pw', t('Name and password', 'Имя и пароль')]];
  const h = big ? 64 : 44;
  return (
    <div style={{ height: h, borderRadius: h / 2, padding: 3, display: 'flex', background: 'rgba(0,0,0,0.35)', border: `1px solid ${HF.hairline}`, ...style }}>
      {opts.map(([id, label]) => (
        <div key={id} style={{ flex: 1, minWidth: 0, borderRadius: h / 2 - 3, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: big ? '0 18px' : '0 10px',
          fontSize: big ? 20 : 14, fontWeight: 700, color: id === value ? HF.text : HF.text2,
          background: id === value ? `linear-gradient(180deg, ${HF.anodLight}, ${HF.anodDark})` : 'transparent',
          boxShadow: id === value ? 'inset 0 1px 0 rgba(255,255,255,0.18), 0 0 0 1px rgba(201,206,216,0.35)' : 'none' }}>
          <div className="hf-ellipsis">{label}</div>
        </div>
      ))}
    </div>
  );
}
// The Quick Connect code: one cell per digit, like the Yandex device code as built. `dim` = no valid code.
function SiCode({ code = '482915', big, dim, style }) {
  const gap = big ? 12 : 8, mid = big ? 36 : 20;
  return (
    <div style={{ display: 'flex', ...style }}>
      {code.split('').map((d, i) => (
        <div key={i} style={{ width: big ? 104 : 54, height: big ? 128 : 64, borderRadius: big ? 22 : 14, marginLeft: i === 0 ? 0 : i === 3 ? mid : gap, flex: '0 0 auto',
          display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(0,0,0,0.35)', border: `1px solid ${HF.glassBorder}`,
          boxShadow: 'inset 0 2px 6px rgba(0,0,0,0.45)', fontSize: big ? 76 : 36, fontWeight: 800 }}>
          {dim ? <div style={{ width: big ? 22 : 12, height: big ? 22 : 12, borderRadius: '50%', border: `${big ? 3 : 2}px solid ${HF.text3}` }} /> : d}
        </div>
      ))}
    </div>
  );
}
// A row of a Settings card: plaque, title, one line under it, trailing control.
function SiSetRow({ icon, title, sub, trailing, first }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 14, minHeight: 64, padding: '10px 0', borderTop: first ? 'none' : `1px solid ${HF.hairline}` }}>
      <IconPlaque icon={icon} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <Txt t="rowTitle" ellipsis>{title}</Txt>
        {sub && <Txt t="caption" color={HF.text2} ellipsis>{sub}</Txt>}
      </div>
      {trailing}
    </div>
  );
}

function useSiWords() {
  const t = useT();
  return {
    intro: t('Your own music server. Enter its address, then confirm a code in Jellyfin or sign in with your account on it.',
      'Ваш собственный музыкальный сервер. Укажите его адрес, а затем подтвердите код в Jellyfin или войдите со своей учётной записью.'),
    httpsNote: t('Only https://. The certificate must be trusted by Android.', 'Только https://. Сертификат должен быть доверенным для Android.'),
    qcHow: t('In Jellyfin, where you are signed in: your profile → Quick Connect → enter this code.',
      'В Jellyfin, где вы уже вошли: профиль → «Быстрое подключение» → введите этот код.'),
    qcWaiting: t('Waiting for confirmation…', 'Ждём подтверждения…'), cancel: t('Cancel', 'Отмена'),
    qcToken: t('No password is typed in YAMP this way. It keeps only the access token issued by the server, encrypted on this device.',
      'Пароль в YAMP при этом не вводится. Хранится только токен доступа, выданный сервером, — в зашифрованном виде на этом устройстве.'),
    order: t('The order matters: Play and Shuffle in Library and the “Liked” tile in the car use the first signed-in source. Drag a card to move it.',
      'Порядок важен: «Слушать» и «Перемешать» в разделе «Музыка» и плитка «Любимое» в авто берут первый подключённый источник. Перетащите карточку, чтобы поменять порядок.'),
    offline: t('Available offline', 'Доступно офлайн'), storage: t('Storage', 'Хранилище'), clear: t('Clear', 'Очистить'),
    clearAll: t('Clear all', 'Очистить всё'), noLink: t('No connection', 'Нет связи'), playDown: t('Play downloaded', 'Слушать скачанное'),
    noLinkMsg: t('The list loads by itself when the network is back. Until then the downloaded music plays without it.',
      'Список загрузится сам, когда сеть появится. А пока можно слушать скачанное — ему связь не нужна.'),
    offlineSum: t('180 tracks · 1.3 GB on this device', '180 треков · 1,3 ГБ на этом устройстве'),
    downloaded: t('Downloaded', 'Скачанные'), cache: t('Playback cache', 'Кэш воспроизведения'), all: t('All', 'Все'),
    mb: (n) => t(`${n} MB`, `${String(n).replace('.', ',')} МБ`), gb: (n) => t(`${n} GB`, `${String(n).replace('.', ',')} ГБ`),
    sources: t('Sources', 'Источники'), yandex: t('Yandex Music', 'Яндекс Музыка'), radio: t('Radio', 'Радио'),
    yandexSub: t('Test Account · Plus active', 'Тестовый Аккаунт · Плюс активен'), notConnected: t('Not connected', 'Не подключён'),
    jellySub: t('Home server · alex', 'Домашний сервер · alex'), radioSub: t('FM stations, no account needed', 'FM-станции, аккаунт не нужен'),
    connect: t('Connect', 'Подключить'), accounts: t('Accounts', 'Аккаунты'), builtIn: t('Built in', 'Встроенные'),
    server: t('Server address', 'Адрес сервера'), user: t('User name', 'Имя пользователя'), password: t('Password', 'Пароль'),
    retry: t('Retry', 'Повторить'), failed: t('Couldn’t load', 'Не удалось загрузить'),
    noAnswer: t('The Jellyfin server did not answer. The app retries by itself as soon as it is reachable.',
      'Сервер Jellyfin не ответил. Приложение повторит само, как только он станет доступен.'),
  };
}

// ── Section A · phone ────────────────────────────────────────────────────────
// A Settings sub-page: the full-screen sheet with back / title / close, like "Account" as built.
function SiSheetPage({ title, badge, children }) {
  return (
    <Frame kind="phone">
      <Scrim opacity={0.5} />
      <Sheet height={883}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '14px 16px 6px' }}>
          <GlassCircle icon="arrow_back" />
          {badge}
          <div className="hf-ellipsis" style={{ flex: 1, minWidth: 0, fontSize: 26, lineHeight: '32px', fontWeight: 800 }}>{title}</div>
          <GlassCircle icon="close" />
        </div>
        <div style={{ padding: '8px 16px 0' }}>{children}</div>
      </Sheet>
    </Frame>
  );
}

function SiSources({ connected }) {
  const t = useT(); const w = useSiWords();
  const card = { padding: '0 14px 0 8px' };
  return (
    <SiSheetPage title={w.sources}>
      <Txt t="body" color={HF.text2} style={{ padding: '0 4px' }}>
        {t('Music from every connected source comes together in Library, Search and queues.', 'Музыка всех подключённых источников собрана вместе — в разделе «Музыка», поиске и очередях.')}
      </Txt>
      <SectionCaps style={{ margin: '20px 4px 10px' }}>{w.accounts}</SectionCaps>
      <SiCard style={card}>
        <SiSourceRow handle provider="yandex" title={w.yandex} sub={w.yandexSub} ok trailing={<Icon name="chevron_right" size={22} color={HF.text3} />} />
      </SiCard>
      {connected ? (
        <SiCard style={{ ...card, marginTop: 10 }}>
          <SiSourceRow handle provider="jellyfin" title="Jellyfin" sub={w.jellySub} ok />
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, height: 48, marginLeft: 6, borderTop: `1px solid ${HF.hairline}` }}>
            <Icon name="dns" size={20} color={HF.text3} />
            <Txt t="body" color={HF.text2}>{t('Server', 'Сервер')}</Txt>
            <Txt t="body" ellipsis style={{ flex: 1, textAlign: 'right' }}>music.example.org</Txt>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, height: 56, marginLeft: 6, borderTop: `1px solid ${HF.hairline}` }}>
            <Icon name="logout" size={22} color={HF.danger} />
            <Txt t="rowTitle" color={HF.danger}>{t('Sign out of Jellyfin', 'Выйти из Jellyfin')}</Txt>
          </div>
        </SiCard>
      ) : (
        <SiCard style={{ ...card, marginTop: 10 }}>
          <SiSourceRow handle provider="jellyfin" title="Jellyfin" sub={w.notConnected} trailing={<GlassPill height={40}>{w.connect}</GlassPill>} />
        </SiCard>
      )}
      <Txt t="caption" color={HF.text3} style={{ margin: '10px 4px 0' }}>{w.order}</Txt>
      {connected && (
        <Txt t="caption" color={HF.text3} style={{ margin: '8px 4px 0' }}>
          {t('Signing out removes the access token from this device. Your queues stay: tracks of that source are shown as unavailable and skipped.',
            'При выходе токен доступа удаляется с устройства. Очереди остаются: треки этого источника показываются недоступными и пропускаются.')}
        </Txt>
      )}
      <SectionCaps style={{ margin: '22px 4px 10px' }}>{w.builtIn}</SectionCaps>
      <SiCard>
        <SiSourceRow provider="radio" title={w.radio} sub={w.radioSub} trailing={<Switch on />} />
      </SiCard>
      <Txt t="caption" color={HF.text3} style={{ margin: '10px 4px 0' }}>
        {t('Switch it off to hide the Radio tab and the radio tile in the car.', 'Выключите, чтобы скрыть вкладку «Радио» и плитку радио в авто.')}
      </Txt>
      {connected && <SiSnackbar>{t('Jellyfin is connected', 'Jellyfin подключён')}</SiSnackbar>}
    </SiSheetPage>
  );
}

// The way "Name and password". state: empty | filled | http | auth | cert | busy
function SiSignIn({ state = 'empty' }) {
  const t = useT(); const w = useSiWords();
  const busy = state === 'busy', typed = state !== 'empty' && state !== 'http';
  const url = state === 'empty' ? '' : state === 'http' ? 'http://music.example.org' : SI_SERVER;
  const urlNote = state === 'http' ? t('Only https:// addresses are supported — the connection must be encrypted.', 'Поддерживаются только адреса https:// — соединение должно быть защищено.')
    : state === 'cert' ? t('Android does not trust this server’s certificate. Self-signed certificates are not supported — use one from a public authority.',
      'Android не доверяет сертификату этого сервера. Самоподписанные сертификаты не поддерживаются — нужен сертификат публичного центра.')
    : w.httpsNote;
  return (
    <SiSheetPage title="Jellyfin" badge={<ProviderBadge provider="jellyfin" size={32} />}>
      <Txt t="body" color={HF.text2} style={{ padding: '0 4px' }}>{w.intro}</Txt>
      <div style={{ opacity: busy ? 0.5 : 1 }}>
        <SiField label={w.server} icon="dns" value={url} placeholder={SI_SERVER} focused={state === 'http'}
          error={state === 'http' || state === 'cert'} note={urlNote} style={{ marginTop: 16 }} />
        {state === 'http' && <GlassPill icon="lock" height={36} style={{ display: 'inline-flex', marginTop: 10 }}>{t('Change to https://', 'Заменить на https://')}</GlassPill>}
        <SiWays value="pw" style={{ marginTop: 16 }} />
        <SiField label={w.user} icon="person" value={typed ? 'alex' : ''} error={state === 'auth'} style={{ marginTop: 16 }} />
        <SiField label={w.password} icon="lock" secure value={typed ? 'x' : ''} error={state === 'auth'}
          note={state === 'auth' ? t('Wrong user name or password.', 'Неверное имя пользователя или пароль.') : null}
          trailing={<Icon name="visibility" size={22} color={HF.text3} fill={0} />} style={{ marginTop: 16 }} />
      </div>
      <MetalPill style={{ marginTop: 24, opacity: typed && !busy ? 1 : 0.4 }}>
        {busy ? <React.Fragment><SiSpinner />{t('Connecting…', 'Подключение…')}</React.Fragment> : w.connect}
      </MetalPill>
      {busy
        ? <TextAction color={HF.text2} style={{ justifyContent: 'center', marginTop: 8 }}>{w.cancel}</TextAction>
        : (
          <div style={{ display: 'flex', gap: 10, margin: '16px 4px 0' }}>
            <Icon name="key" size={18} color={HF.text3} />
            <Txt t="caption" color={HF.text3} style={{ flex: 1 }}>
              {t('The password is not saved. YAMP keeps only the access token issued by the server, encrypted on this device.',
                'Пароль не сохраняется. YAMP хранит только токен доступа, выданный сервером, — в зашифрованном виде на этом устройстве.')}
            </Txt>
          </div>
        )}
    </SiSheetPage>
  );
}

// The way "Quick Connect" (D31). state: code | expired | off
function SiQuick({ state = 'code' }) {
  const t = useT(); const w = useSiWords();
  return (
    <SiSheetPage title="Jellyfin" badge={<ProviderBadge provider="jellyfin" size={32} />}>
      <Txt t="body" color={HF.text2} style={{ padding: '0 4px' }}>{w.intro}</Txt>
      <SiField label={w.server} icon="dns" value={SI_SERVER} note={w.httpsNote} style={{ marginTop: 16 }} />
      <SiWays value="qc" style={{ marginTop: 16 }} />
      {state === 'off' && (
        <SiState icon="key_off" style={{ padding: '32px 16px 0' }} title={t('Quick Connect is off on this server', 'На этом сервере быстрое подключение выключено')}
          message={t('The server’s administrator switches it on in the Jellyfin dashboard. Your user name and password work without it.',
            'Его включает администратор сервера в панели управления Jellyfin. По имени и паролю войти можно и без него.')}
          action={t('Sign in with a password', 'Войти по паролю')} />
      )}
      {state !== 'off' && <SiCode dim={state === 'expired'} style={{ marginTop: 22, padding: '0 2px' }} />}
      {state === 'code' && (
        <React.Fragment>
          <Txt t="body" style={{ margin: '16px 4px 0' }}>{w.qcHow}</Txt>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, margin: '18px 4px 0' }}>
            <SiSpinner /><Txt t="body" color={HF.text2}>{w.qcWaiting}</Txt>
          </div>
          <GlassPill style={{ marginTop: 22 }}>{w.cancel}</GlassPill>
          <div style={{ display: 'flex', gap: 10, margin: '16px 4px 0' }}>
            <Icon name="key" size={18} color={HF.text3} />
            <Txt t="caption" color={HF.text3} style={{ flex: 1 }}>{w.qcToken}</Txt>
          </div>
        </React.Fragment>
      )}
      {state === 'expired' && (
        <React.Fragment>
          <Txt t="rowTitle" style={{ margin: '16px 4px 0' }}>{t('The code has expired', 'Код устарел')}</Txt>
          <Txt t="body" color={HF.text2} style={{ margin: '4px 4px 0' }}>
            {t('A code lives for a few minutes. Get a new one and confirm it in Jellyfin.', 'Код действует несколько минут. Возьмите новый и подтвердите его в Jellyfin.')}
          </Txt>
          <MetalPill icon="refresh" style={{ marginTop: 22 }}>{t('Get a new code', 'Получить новый код')}</MetalPill>
        </React.Fragment>
      )}
    </SiSheetPage>
  );
}

// ── Section A · head unit ────────────────────────────────────────────────────
// Settings as built in landscape: one wide sheet, sections on the left, content on the right.
// `active` = the section shown; `children` replace the right side (default: the Sources list).
function SiHeadSettings({ active = 'hub', children }) {
  const t = useT(); const w = useSiWords();
  const nav = [
    ['hub', w.sources, t('Yandex Music · Radio', 'Яндекс Музыка · Радио')],
    ['play_arrow', t('Playback', 'Воспроизведение'), t('Maximum · crossfade 3 s', 'Максимальное · кроссфейд 3 с')],
    ['storage', w.storage, t('1.3 GB · 180 tracks offline', '1,3 ГБ · 180 треков офлайн')],
    ['dashboard', t('Interface', 'Интерфейс'), t('Opens: My Wave · wheel on the left', 'Открывать: Моя волна · руль левый')],
    ['info', t('About', 'О приложении'), t('Version 1.12.2', 'Версия 1.12.2')],
  ];
  const card = { padding: '0 20px 0 10px' };
  return (
    <div style={{ position: 'absolute', left: 200, right: 200, top: 16, bottom: -28, background: HF.sheet, borderRadius: 28, border: `1px solid ${HF.hairline}` }}>
      <div style={{ width: 44, height: 5, borderRadius: 3, background: HF.text3, margin: '10px auto 0' }} />
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '8px 32px 14px' }}>
        <div style={{ fontSize: 36, lineHeight: '42px', fontWeight: 800 }}>{t('Settings', 'Настройки')}</div>
        <GlassCircle icon="close" size={60} />
      </div>
      <div style={{ display: 'flex', gap: 28, padding: '0 32px 0 16px' }}>
        <div style={{ width: 430, flex: '0 0 auto', display: 'flex', flexDirection: 'column', gap: 6 }}>
          {nav.map(([icon, title, sub]) => (
            <div key={icon} style={{ height: 88, borderRadius: 22, padding: '0 16px', display: 'flex', alignItems: 'center', gap: 16,
              background: icon === active ? HF.glassFill : 'transparent', border: `1px solid ${icon === active ? HF.glassBorder : 'transparent'}` }}>
              <IconPlaque icon={icon} size={52} />
              <div style={{ minWidth: 0 }}>
                <div className="hf-ellipsis" style={{ fontSize: 22, lineHeight: '28px', fontWeight: 700 }}>{title}</div>
                <div className="hf-ellipsis" style={{ fontSize: 16, lineHeight: '22px', color: HF.text2 }}>{sub}</div>
              </div>
            </div>
          ))}
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          {children || (
            <React.Fragment>
              <div style={{ ...HFType.sectionCaps, fontSize: 15, color: HF.text2, margin: '6px 4px 10px' }}>{w.accounts}</div>
              <SiCard style={card}>
                <SiSourceRow big handle provider="yandex" title={w.yandex} sub={w.yandexSub} ok trailing={<Icon name="chevron_right" size={30} color={HF.text3} />} />
              </SiCard>
              <SiCard style={{ ...card, paddingRight: 14, marginTop: 10 }}>
                <SiSourceRow big handle provider="jellyfin" title="Jellyfin" sub={w.notConnected} trailing={<GlassPill height={60} style={{ fontSize: 20 }}>{w.connect}</GlassPill>} />
              </SiCard>
              <div style={{ fontSize: 16, lineHeight: '22px', color: HF.text3, margin: '10px 4px 0' }}>{w.order}</div>
              <div style={{ ...HFType.sectionCaps, fontSize: 15, color: HF.text2, margin: '22px 4px 10px' }}>{w.builtIn}</div>
              <SiCard style={{ padding: '0 20px' }}>
                <SiSourceRow big provider="radio" title={w.radio} sub={w.radioSub} trailing={<Switch on />} />
              </SiCard>
            </React.Fragment>
          )}
        </div>
      </div>
    </div>
  );
}
// The system keyboard, drawn only to show how much of a 720-high screen it takes.
function SiKeyboard({ height = 300 }) {
  const key = (ch, flex = 1, on) => (
    <div key={ch} style={{ flex, height: 60, borderRadius: 12, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 24, fontWeight: 600,
      color: on ? HF.text : HF.text2, background: on ? HF.accent : HF.glassFill }}>{ch}</div>
  );
  const row = (keys, pad) => <div style={{ display: 'flex', gap: 8, padding: `0 ${pad}px` }}>{keys}</div>;
  return (
    <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height, background: HF.anodDark, borderTop: `1px solid ${HF.hairline}`,
      padding: '14px 240px 0', display: 'flex', flexDirection: 'column', gap: 8 }}>
      {row('qwertyuiop'.split('').map((c) => key(c)), 0)}
      {row('asdfghjkl'.split('').map((c) => key(c)), 72)}
      {row([key('⇧', 1.5), ...'zxcvbnm'.split('').map((c) => key(c)), key('⌫', 1.5)], 0)}
      {row([key('?123', 1.5), key('/'), key(' ', 5), key('.'), key('→', 1.5, true)], 0)}
    </div>
  );
}

// The sign-in panel over Settings: title, then the address and the choice of the way in one row.
function SiHeadPanel({ way, top, children }) {
  const w = useSiWords();
  return (
    <React.Fragment>
      <SiHeadSettings />
      <Scrim opacity={0.45} />
      <div style={{ position: 'absolute', left: 290, right: 290, top, background: HF.dialog, borderRadius: HFShape.dialog, border: `1px solid ${HF.hairline}`,
        boxShadow: '0 24px 70px rgba(0,0,0,0.7)', padding: '22px 28px 24px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <ProviderBadge provider="jellyfin" size={44} />
          <div style={{ fontSize: 36, lineHeight: '42px', fontWeight: 800 }}>Jellyfin</div>
          <div style={{ flex: 1 }} />
          <GlassCircle icon="close" size={60} />
        </div>
        <div style={{ display: 'flex', alignItems: 'flex-end', gap: 16, marginTop: 12 }}>
          <SiField big label={w.server} icon="dns" value={SI_SERVER} style={{ flex: 1 }} />
          <SiWays big value={way} style={{ flex: 1 }} />
        </div>
        {children}
      </div>
    </React.Fragment>
  );
}

// Suggested on the head unit: after the address nothing more is typed.
function SiHeadQuick() {
  const w = useSiWords();
  return (
    <Frame kind="head">
      <SiHeadPanel way="qc" top={104}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 44, marginTop: 28 }}>
          <SiCode big style={{ flex: '0 0 auto' }} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 22, lineHeight: '30px', fontWeight: 600 }}>{w.qcHow}</div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginTop: 16 }}>
              <SiSpinner size={28} /><div className="hf-ellipsis" style={{ fontSize: 20, color: HF.text2 }}>{w.qcWaiting}</div>
            </div>
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 24, marginTop: 28 }}>
          <Icon name="key" size={24} color={HF.text3} />
          <div style={{ flex: 1, minWidth: 0, fontSize: 17, lineHeight: '23px', color: HF.text2 }}>{w.qcToken}</div>
          <GlassPill height={68} style={{ fontSize: 22, padding: '0 56px', flex: '0 0 auto' }}>{w.cancel}</GlassPill>
        </div>
      </SiHeadPanel>
    </Frame>
  );
}

// The alternative: everything stays above the system keyboard.
function SiHeadSignIn() {
  const t = useT(); const w = useSiWords();
  return (
    <Frame kind="head">
      <SiHeadPanel way="pw" top={16}>
        <div style={{ display: 'flex', alignItems: 'flex-end', gap: 16, marginTop: 14 }}>
          <SiField big label={w.user} icon="person" value="alex" style={{ flex: 1 }} />
          <SiField big label={w.password} icon="lock" secure value="x" focused
            trailing={<Icon name="visibility" size={26} color={HF.text3} fill={0} />} style={{ flex: 1 }} />
          <MetalPill height={64} style={{ fontSize: 22, padding: '0 56px', flex: '0 0 auto' }}>{w.connect}</MetalPill>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 14 }}>
          <Icon name="key" size={22} color={HF.text3} />
          <div className="hf-ellipsis" style={{ flex: 1, minWidth: 0, fontSize: 17, lineHeight: '23px', color: HF.text2 }}>
            {t('Only https://, with a certificate Android trusts. The password is not saved — only the server’s access token.',
              'Только https:// с сертификатом, которому доверяет Android. Пароль не сохраняется — только токен доступа сервера.')}
          </div>
        </div>
      </SiHeadPanel>
      <SiKeyboard />
    </Frame>
  );
}

// ── Section B · phone ────────────────────────────────────────────────────────
// A Jellyfin folder with its list replaced by a state. The header is known before the list arrives.
function SiFolderScreen({ name, meta, tint, children }) {
  const fw = useFolderWords();
  return (
    <Frame kind="phone" tint={tint}>
      <div style={{ position: 'absolute', left: 16, right: 16, top: 20, display: 'flex', alignItems: 'center', gap: 12 }}>
        <GlassCircle icon="arrow_back" /><Crumbs path={[fw.folders, name]} />
      </div>
      <div style={{ position: 'absolute', left: 16, right: 16, top: 84 }}>
        <div className="hf-ellipsis" style={{ fontSize: 26, lineHeight: '31px', fontWeight: 800 }}>{name}</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, height: 17, margin: '6px 0 16px' }}>
          <ProviderBadge provider="jellyfin" size={16} />
          {meta ? <Txt t="caption" color={HF.text2}>{meta}</Txt> : <div style={{ width: 132, height: 10, borderRadius: 5, background: HF.glassFill }} />}
        </div>
        <div style={{ display: 'flex', gap: 10, opacity: 0.4 }}>
          <MetalPill icon="play_arrow" style={{ flex: 1 }}>{fw.play}</MetalPill>
          <GlassPill icon="shuffle" style={{ flex: 1 }}>{fw.shuffle}</GlassPill>
        </div>
        {children}
      </div>
      <PhoneChrome active="library" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />
    </Frame>
  );
}

function SiListLoading() {
  const fw = useFolderWords();
  return (
    <SiFolderScreen name="Pink Floyd" tint="#8A5A22">
      <SectionCaps style={{ margin: '22px 4px 14px' }}>{fw.folders}</SectionCaps>
      <div style={{ padding: '0 4px' }}><SiSkeletonRows count={6} /></div>
    </SiFolderScreen>
  );
}
function SiListEmpty() {
  const t = useT();
  return (
    <SiFolderScreen name="Детские" meta={t('Empty', 'Пусто')} tint="#7A2E48">
      <SiState icon="folder_off" title={t('No music here', 'Здесь нет музыки')} action={t('Refresh', 'Обновить')}
        message={t('This folder has no tracks or subfolders. New files appear after the server rescans its library.',
          'В этой папке нет треков и вложенных папок. Новые файлы появятся, когда сервер пересканирует библиотеку.')} />
    </SiFolderScreen>
  );
}
function SiListError() {
  const w = useSiWords();
  return (
    <SiFolderScreen name="Pink Floyd" meta="Jellyfin" tint="#8A5A22">
      <SiState icon="cloud_off" title={w.failed} message={w.noAnswer} action={w.retry} />
    </SiFolderScreen>
  );
}
// No network at all, on a playlist as built (playlist.png): not a dead end — the one action plays what is
// already on the device (D33). With nothing downloaded the state has no action.
function SiListOffline() {
  const t = useT(); const fw = useFolderWords(); const w = useSiWords();
  return (
    <Frame kind="phone">
      <div style={{ position: 'absolute', left: 16, right: 16, top: 20, display: 'flex', justifyContent: 'space-between' }}>
        <GlassCircle icon="arrow_back" /><GlassCircle icon="more_vert" />
      </div>
      <div style={{ position: 'absolute', left: 16, right: 16, top: 80 }}>
        <div style={{ display: 'flex', gap: 14, alignItems: 'center', marginBottom: 16 }}>
          <Cover size={96} hue="gray" radius={16} glyph="queue_music" />
          <div style={{ minWidth: 0 }}>
            <div className="hf-ellipsis" style={{ fontSize: 24, lineHeight: '29px', fontWeight: 800 }}>{t('On the road', 'В дорогу')}</div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 6 }}>
              <ProviderBadge provider="yandex" size={16} /><Txt t="caption" color={HF.text2}>{fw.nTracks(42)}</Txt>
            </div>
          </div>
        </div>
        <div style={{ display: 'flex', gap: 10, opacity: 0.4 }}>
          <MetalPill icon="play_arrow" style={{ flex: 1 }}>{fw.play}</MetalPill>
          <GlassPill icon="shuffle" style={{ flex: 1 }}>{fw.shuffle}</GlassPill>
        </div>
        <SiState icon="wifi_off" title={w.noLink} message={w.noLinkMsg} action={w.playDown} actionIcon="play_arrow" />
      </div>
      <PhoneChrome active="library" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />
    </Frame>
  );
}

// A queue that mixes sources after signing out of one of them (Q7 / D22).
function SiQueueUnavailable() {
  const t = useT();
  const gone = t('unavailable', 'недоступен');
  const x = <GlassCircle icon="close" size={36} />;
  return (
    <Frame kind="phone" tint="#7A2E48" glowY="18%">
      <Scrim opacity={0.45} />
      <Sheet height={780}>
        <QueueSheetHeader title={t('Solo drive', 'Один за рулём')} subtitle={t('Queue · 3 of 112', 'Очередь · 3 из 112')}
          trailing={<GlassPill icon="stacks" height={44}>{t('Queues', 'Очереди')}</GlassPill>} />
        <div style={{ margin: '4px 12px 0', borderRadius: 18, padding: '12px 12px 12px 14px', display: 'flex', alignItems: 'center', gap: 12,
          background: HF.glassFill, border: `1px solid ${HF.glassBorder}` }}>
          <ProviderBadge provider="yandex" size={32} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <Txt t="cardTitle">{t('3 tracks need Yandex Music', 'Для 3 треков нужна Яндекс Музыка')}</Txt>
            <Txt t="caption" color={HF.text2} style={{ marginTop: 2 }}>{t('Skipped until you sign in', 'Пропускаются, пока вы не войдёте')}</Txt>
          </div>
          <MetalPill height={44} style={{ fontSize: 15, padding: '0 18px', flex: '0 0 auto' }}>{t('Sign in', 'Войти')}</MetalPill>
        </div>
        <div style={{ padding: '4px 20px' }}>
          <SectionCaps style={{ margin: '14px 0 8px' }}>{t('Now playing', 'Сейчас играет')}</SectionCaps>
          <div style={{ borderRadius: 18, padding: '0 12px', margin: '0 -8px', background: `linear-gradient(180deg, ${HF.anodLight}, ${HF.anodDark})`,
            boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.20), 0 0 0 1.5px rgba(201,206,216,0.50)' }}>
            <TrackRow title="Du hast" artist="Rammstein" hue="rose" provider="jellyfin" trailing={<Icon name="graphic_eq" size={24} />} />
          </div>
          <SectionCaps style={{ margin: '18px 0 4px' }}
            trailing={<div style={{ display: 'flex', gap: 14 }}><Icon name="shuffle" size={22} color={HF.text2} /><Icon name="repeat" size={22} color={HF.text2} /></div>}>{t('Next', 'Далее')}</SectionCaps>
          <TrackRow title="Кукушка" artist={`Кино · ${gone}`} hue="gray" provider="yandex" unavailable trailing={x} />
          <TrackRow title="Enter Sandman" artist="Metallica" hue="gray" provider="jellyfin" trailing={x} />
          <TrackRow title="Выхода нет" artist={`Сплин · ${gone}`} hue="blue" provider="yandex" unavailable trailing={x} />
          <TrackRow title="Bohemian Rhapsody" artist="Queen" hue="rose" provider="jellyfin" trailing={x} />
          <TrackRow title="Штиль" artist={`Ария · ${gone}`} hue="teal" provider="yandex" unavailable trailing={x} />
          <TrackRow title="Comfortably Numb" artist="Pink Floyd" hue="violet" provider="jellyfin" trailing={x} />
        </div>
      </Sheet>
    </Frame>
  );
}

// ── Section B · head unit ────────────────────────────────────────────────────
// `offline` = no connection at all: "Play downloaded" instead of Retry (D33).
function SiHeadError({ offline }) {
  const fw = useFolderWords(); const w = useSiWords();
  const pill = { fontSize: 22 };
  return (
    <Frame kind="head" tint="#8A5A22" glowY="20%">
      <div style={{ position: 'absolute', left: 48, right: 48, top: 28, display: 'flex', alignItems: 'center', gap: 16 }}>
        <GlassCircle icon="arrow_back" size={60} />
        <Crumbs big path={[fw.folders, 'Pink Floyd']} />
        <div style={{ flex: 1 }} />
        <NowPlayingCapsule />
      </div>
      <div style={{ position: 'absolute', left: 48, top: 130, width: 470 }}>
        <div className="hf-ellipsis" style={{ fontSize: 38, lineHeight: '44px', fontWeight: 800 }}>Pink Floyd</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, margin: '8px 0 22px' }}>
          <ProviderBadge provider="jellyfin" size={22} /><div style={{ fontSize: 20, color: HF.text2 }}>Jellyfin</div>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14, opacity: 0.4 }}>
          <MetalPill icon="play_arrow" height={76} style={pill}>{fw.play}</MetalPill>
          <GlassPill icon="shuffle" height={76} style={pill}>{fw.shuffle}</GlassPill>
          <GlassPill icon="sensors" height={76} style={pill}>{fw.radio}</GlassPill>
          <GlassPill icon="stacks" height={76} style={pill}>{fw.add}</GlassPill>
        </div>
      </div>
      {offline
        ? <SiState big icon="wifi_off" title={w.noLink} message={w.noLinkMsg} action={w.playDown} actionIcon="play_arrow"
          style={{ position: 'absolute', left: 566, right: 48, top: 190 }} />
        : <SiState big icon="cloud_off" title={w.failed} message={w.noAnswer} action={w.retry}
          style={{ position: 'absolute', left: 566, right: 48, top: 190 }} />}
    </Frame>
  );
}

// ── Section B · Settings → Storage → Available offline (D33) ─────────────────
// Downloaded and cached music has no row in Library; this is where it is browsed and managed.
// [title, artist, cover hue, provider, size in MB]
const SI_DOWNLOADED = [['Wish You Were Here', 'Pink Floyd', 'amber', 'jellyfin', '12.9'], ['Кукушка', 'Кино', 'gray', 'yandex', '9.1'],
  ['Дороги', 'Мельница', 'green', 'jellyfin', '9.8'], ['Bohemian Rhapsody', 'Queen', 'rose', 'jellyfin', '14.2'],
  ['Выхода нет', 'Сплин', 'blue', 'yandex', '8.4'], ['Enter Sandman', 'Metallica', 'gray', 'jellyfin', '12.7']];
const SI_CACHED = [['Штиль', 'Ария', 'teal', 'yandex', '10.6'], ['Группа крови', 'Кино', 'violet', 'yandex', '9.9']];

function SiStorage() {
  const t = useT(); const w = useSiWords(); const fw = useFolderWords();
  const clear = <TextAction color={HF.danger}>{w.clear}</TextAction>;
  const total = (mark, name, n, size, last) => (
    <div key={name} style={{ display: 'flex', alignItems: 'center', gap: 12, height: 44, borderBottom: last ? 'none' : `1px solid ${HF.hairline}` }}>
      <div style={{ width: 24, display: 'flex', justifyContent: 'center' }}>{mark}</div>
      <Txt t="body" ellipsis style={{ flex: 1, minWidth: 0 }}>{name}</Txt>
      <Txt t="caption" color={HF.text3}>{fw.nTracks(n)}</Txt>
      <Txt t="body" style={{ width: 62, textAlign: 'right', fontWeight: 700 }}>{size}</Txt>
    </div>
  );
  return (
    <SiSheetPage title={w.storage}>
      <SiCard style={{ padding: '14px 14px 2px' }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, padding: '0 2px 8px' }}>
          <div style={{ fontSize: 30, lineHeight: '36px', fontWeight: 800 }}>{w.gb('1.3')}</div>
          <Txt t="caption" color={HF.text2} ellipsis style={{ flex: 1, minWidth: 0 }}>{t('of music on this device', 'музыки на этом устройстве')}</Txt>
        </div>
        {total(<ProviderBadge provider="yandex" size={22} />, w.yandex, 48, w.gb('0.4'))}
        {total(<ProviderBadge provider="jellyfin" size={22} />, 'Jellyfin', 120, w.gb('0.8'))}
        {total(<Icon name="cached" size={22} color={HF.text2} />, w.cache, 12, w.mb('64'), true)}
      </SiCard>
      <SiCard style={{ marginTop: 10 }}>
        <SiSetRow first icon="download_done" title={w.offline} sub={t('180 tracks · play, shuffle, remove', '180 треков · слушать, удалять')}
          trailing={<Icon name="chevron_right" size={22} color={HF.text3} />} />
      </SiCard>
      <SectionCaps style={{ margin: '22px 4px 10px' }}>{t('Free up space', 'Освободить место')}</SectionCaps>
      <SiCard style={{ paddingRight: 6 }}>
        <SiSetRow first icon="download" title={w.downloaded} sub={t('168 tracks · 1.2 GB', '168 треков · 1,2 ГБ')} trailing={clear} />
        <SiSetRow icon="cached" title={w.cache} sub={t('Filled 64 MB of 100 MB', 'Заполнено 64 МБ из 100 МБ')} trailing={clear} />
      </SiCard>
      <SectionCaps style={{ margin: '22px 4px 10px' }}>{t('Where to store', 'Где хранить')}</SectionCaps>
      <Segmented options={[t('Device memory', 'Память устройства'), t('SD card', 'SD-карта')]} value={t('Device memory', 'Память устройства')} style={{ height: 44, borderRadius: 22 }} />
      <Txt t="caption" color={HF.text3} style={{ margin: '10px 4px 0' }}>
        {t('19.9 GB free on the device. The cache size and auto-download are set in Playback.', 'На устройстве свободно 19,9 ГБ. Размер кэша и автоскачивание настраиваются в разделе «Воспроизведение».')}
      </Txt>
    </SiSheetPage>
  );
}

function SiSourcePills({ big }) {
  const w = useSiWords();
  const h = big ? 56 : 36, st = big ? { height: h, borderRadius: h / 2, padding: '0 22px', fontSize: 18, gap: 10 } : undefined;
  return (
    <div style={{ display: 'flex', gap: big ? 10 : 8 }}>
      <FilterPill active style={st}>{w.all} · 180</FilterPill>
      <FilterPill style={st}><ProviderBadge provider="yandex" size={big ? 24 : 18} />{w.yandex}</FilterPill>
      <FilterPill style={st}><ProviderBadge provider="jellyfin" size={big ? 24 : 18} />Jellyfin</FilterPill>
    </div>
  );
}

function SiOfflineList() {
  const w = useSiWords(); const fw = useFolderWords();
  const del = <GlassCircle icon="delete" size={36} color={HF.text2} />;
  const row = ([title, artist, hue, provider, mb]) => <TrackRow key={title} title={title} artist={artist} hue={hue} provider={provider} duration={w.mb(mb)} trailing={del} />;
  return (
    <SiSheetPage title={w.offline}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '0 4px', marginTop: -6 }}>
        <Txt t="caption" color={HF.text2} ellipsis style={{ flex: 1, minWidth: 0 }}>{w.offlineSum}</Txt>
        <TextAction color={HF.danger} style={{ padding: 0, flex: '0 0 auto' }}>{w.clearAll}</TextAction>
      </div>
      <div style={{ display: 'flex', gap: 10, marginTop: 6 }}>
        <MetalPill icon="play_arrow" style={{ flex: 1 }}>{fw.play}</MetalPill>
        <GlassPill icon="shuffle" style={{ flex: 1 }}>{fw.shuffle}</GlassPill>
      </div>
      <div style={{ marginTop: 14 }}><SiSourcePills /></div>
      <div style={{ padding: '0 4px' }}>
        <SectionCaps style={{ margin: '18px 0 4px' }}>{w.downloaded} · 168</SectionCaps>
        {SI_DOWNLOADED.map(row)}
        <SectionCaps style={{ margin: '14px 0 4px' }}>{w.cache} · 12</SectionCaps>
        {SI_CACHED.map(row)}
      </div>
    </SiSheetPage>
  );
}

// Head unit: the same list on the right side of the landscape Settings sheet.
function SiHeadOfflineList() {
  const w = useSiWords(); const fw = useFolderWords();
  const pill = { fontSize: 20, padding: '0 30px', flex: '0 0 auto' };
  return (
    <Frame kind="head">
      <SiHeadSettings active="storage">
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <GlassCircle icon="arrow_back" size={60} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="hf-ellipsis" style={{ fontSize: 28, lineHeight: '34px', fontWeight: 800 }}>{w.offline}</div>
            <div className="hf-ellipsis" style={{ fontSize: 16, lineHeight: '22px', color: HF.text2 }}>{w.offlineSum}</div>
          </div>
          <MetalPill icon="play_arrow" height={64} style={pill}>{fw.play}</MetalPill>
          <GlassPill icon="shuffle" height={64} style={pill}>{fw.shuffle}</GlassPill>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', marginTop: 14 }}>
          <SiSourcePills big />
          <div style={{ flex: 1 }} />
          <div style={{ height: 56, padding: '0 8px', display: 'flex', alignItems: 'center', gap: 8, fontSize: 18, fontWeight: 700, color: HF.danger }}>
            <Icon name="delete_sweep" size={26} color={HF.danger} />{w.clearAll}
          </div>
        </div>
        <div style={{ ...HFType.sectionCaps, fontSize: 15, color: HF.text2, margin: '16px 4px 4px' }}>{w.downloaded} · 168</div>
        {SI_DOWNLOADED.map(([title, artist, hue, provider, mb], i) => (
          <div key={title} style={{ display: 'flex', alignItems: 'center', gap: 16, height: 76, padding: '0 0 0 4px', borderTop: i ? `1px solid ${HF.hairline}` : 'none' }}>
            <Cover size={56} hue={hue} radius={12} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="hf-ellipsis" style={{ fontSize: 20, lineHeight: '26px', fontWeight: 700 }}>{title}</div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <ProviderBadge provider={provider} size={18} /><div className="hf-ellipsis" style={{ fontSize: 16, lineHeight: '22px', color: HF.text2 }}>{artist}</div>
              </div>
            </div>
            <div style={{ fontSize: 16, color: HF.text3 }}>{w.mb(mb)}</div>
            <GlassCircle icon="delete" size={56} iconSize={26} color={HF.text2} />
          </div>
        ))}
      </SiHeadSettings>
    </Frame>
  );
}

function signinSections() {
  const P = { w: FRAMES.phone.w, h: FRAMES.phone.h }, H = { w: FRAMES.head.w, h: FRAMES.head.h };
  return [
    { id: 'si-signin', title: '9 · Sources and Jellyfin sign-in (Phase 4)',
      subtitle: 'Several sources can be signed in at once (C7, D19); Settings → Sources lists them and their order matters — the first signed-in source serves Library Play / Shuffle and the car “Liked” tile (D30). Jellyfin sign-in: after the https address (certificate trusted by Android, no self-signed) there are two ways — Quick Connect, a code confirmed in a Jellyfin client that is already signed in (D31), or user name and password (DESIGN_GUIDE §8: never prefilled or stored, only the token). Sign-in lives in the normal landscape interface; car mode never needs it (D32).',
      boards: [
        { id: 'si-sources', label: 'Settings → Sources · Yandex signed in, Jellyfin not connected, Radio switch; cards are dragged to reorder — the first one is used where a single button acts alone (D30)', ...P, el: <SiSources /> },
        { id: 'si-empty', label: 'Jellyfin sign-in · empty: the address, then the choice of the way; the phone opens on name and password (Connect is disabled until all three are filled)', ...P, el: <SiSignIn state="empty" /> },
        { id: 'si-filled', label: 'Name and password · filled in, ready to connect', ...P, el: <SiSignIn state="filled" /> },
        { id: 'si-err-http', label: 'Error · http:// typed — inline, with a one-tap fix', ...P, el: <SiSignIn state="http" /> },
        { id: 'si-err-auth', label: 'Error · wrong user name or password (the typed password stays until the sheet closes)', ...P, el: <SiSignIn state="auth" /> },
        { id: 'si-err-cert', label: 'Error · certificate not trusted — the same line reads “Can’t reach the server. Check the address and the connection.” when the host does not answer', ...P, el: <SiSignIn state="cert" /> },
        { id: 'si-connecting', label: 'Connecting · fields locked, button disabled, Cancel', ...P, el: <SiSignIn state="busy" /> },
        { id: 'si-qc-code', label: 'Quick Connect · choosing it asks the server for a code; the app signs in by itself once the code is confirmed in Jellyfin (D31)', ...P, el: <SiQuick state="code" /> },
        { id: 'si-qc-expired', label: 'Quick Connect · the code expired — Get a new code', ...P, el: <SiQuick state="expired" /> },
        { id: 'si-qc-off', label: 'Quick Connect · switched off on the server — back to name and password', ...P, el: <SiQuick state="off" /> },
        { id: 'si-connected', label: 'Connected · Jellyfin with server name and user, Sign out in its card; a new source joins the end of the order', ...P, el: <SiSources connected /> },
        { id: 'si-head-sources', label: 'Head unit · Settings → Sources in landscape: the same cards with drag handles and the order caption (D30)', ...H, el: <Frame kind="head"><SiHeadSettings /></Frame> },
        { id: 'si-head', label: 'Head unit · Quick Connect is the suggested way here: the address is typed once, then a big code to confirm in Jellyfin — nothing more to type', ...H, el: <SiHeadQuick /> },
        { id: 'si-head-password', label: 'Head unit · the alternative, name and password — everything stays above the system keyboard (drawn for scale)', ...H, el: <SiHeadSignIn /> },
      ] },
    { id: 'si-states', title: '13 · List states, unavailable sources, music available offline',
      subtitle: 'Every list goes through LoadState: skeleton rows → content, an empty state, or an error (DESIGN_GUIDE §3); the header and actions stay, dimmed. The server did not answer → Retry; no connection at all → the single action is “Play downloaded”, not a dead end (D33). Downloaded and cached music has no row in Library: it is browsed and managed in Settings → Storage → Available offline (D33). Queue entries of a source that is not signed in are greyed and skipped during playback, never removed (Q7, D22).',
      boards: [
        { id: 'si-list-loading', label: 'Loading · skeleton rows (static in lite mode)', ...P, el: <SiListLoading /> },
        { id: 'si-list-empty', label: 'Empty · a Jellyfin folder with no music', ...P, el: <SiListEmpty /> },
        { id: 'si-list-error', label: 'Error · the server did not answer — Retry', ...P, el: <SiListError /> },
        { id: 'si-list-offline', label: 'No connection (a Yandex playlist) · Play downloaded starts what is on the device; the list loads by itself when the network is back (no action when nothing is downloaded)', ...P, el: <SiListOffline /> },
        { id: 'si-queue-unavailable', label: 'Queue after signing out of Yandex · banner + greyed entries that are skipped', ...P, el: <SiQueueUnavailable /> },
        { id: 'si-storage', label: 'Settings → Storage · totals by source and for the playback cache, the “Available offline” entry, clearing, where to store', ...P, el: <SiStorage /> },
        { id: 'si-storage-list', label: 'Available offline · filter by source, Play / Shuffle, size per track, remove one, Clear all (asks first)', ...P, el: <SiOfflineList /> },
        { id: 'si-head-error', label: 'Head unit · the server did not answer — Retry inside a folder (76 dp button)', ...H, el: <SiHeadError /> },
        { id: 'si-head-offline', label: 'Head unit · no connection — Play downloaded (76 dp button)', ...H, el: <SiHeadError offline /> },
        { id: 'si-head-storage-list', label: 'Head unit · Settings → Storage → Available offline: 76 dp rows, 56 dp remove buttons', ...H, el: <SiHeadOfflineList /> },
      ] },
  ];
}

Object.assign(window, { signinSections });
