// jellyfin.jsx — Phase 4: Jellyfin as a second provider (docs/phase-3-providers.md step 20; D78, D79, D82–D85).
// A components-and-screens module like accounts.jsx: no sections of its own. jellyfinBoards() returns the variants,
// each placed next to the board it extends (YAMP.html adds them to sections 4, 5, 9, 11b and 13).
// Builds on the Phase 2a account model: a Jellyfin account is one server + one user (D78), it has its own default
// queue from sign-in (D83), and a queue holds tracks of its own account only (D84).
// Sample data is neutral: an invented server "Home Media", invented free-music-style albums, no real addresses.
// Every top-level name here starts with `Jf` (the canvas scripts share one global scope).

// ── Sample account, queues and music ────────────────────────────────────────
function useJfAccount() {
  const t = useT();
  return { id: 'jf', name: 'Home Media', initial: 'H', color: '#8E6FD8', user: 'alex', version: 'Jellyfin 10.10.3',
    sub: t('alex · music.example.org', 'alex · music.example.org') };
}
// The Yandex queues of the Phase 2a boards plus the Jellyfin account's default queue (D83) and one of its own.
function useJfQueues() {
  const t = useT(); const jf = useJfAccount();
  const yandex = useAcQueues().filter((q) => ['a-wave', 'a-family', 'r-wave', 'r-drive'].includes(q.id)).map((q) => ({ ...q, active: false }));
  return [
    ...yandex,
    { id: 'j-home', acc: jf, kind: 'static', name: 'Home Media', track: 'Night Drive', artist: 'Lumen Lane', hue: 'violet', pos: 4, total: 37, progress: 0.38, active: true },
    { id: 'j-calm', acc: jf, kind: 'static', name: t('Calm evening', 'Спокойный вечер'), track: 'Paper Lanterns', artist: 'The Quiet Coast', hue: 'teal', pos: 1, total: 22, progress: 0.05 },
  ];
}
const JF_ALBUM = [['Night Drive', '4:12'], ['Neon Harbour', '3:47'], ['Slow Signal', '5:03'], ['Afterglow Avenue', '3:58'], ['Tunnel Lights', '4:31'], ['Last Exit', '6:10']];
const JF_PLAYLIST = [['Paper Lanterns', 'The Quiet Coast', 'teal', '3:21'], ['Night Drive', 'Lumen Lane', 'violet', '4:12'], ['Morning Tide', 'Field Notes', 'blue', '2:58'],
  ['Copper Sky', 'Hollow Pines', 'amber', '4:40'], ['Small Hours', 'The Quiet Coast', 'teal', '3:05'], ['Low Orbit', 'Static Bloom', 'rose', '5:16']];

// ── Components ───────────────────────────────────────────────────────────────
// Grouped / flat list over the three accounts (the Phase 2a AcList groups only its two Yandex accounts).
function JfList({ queues, grouped, big }) {
  const a = useAcAccounts(); const jf = useJfAccount();
  const gap = big ? 6 : 4;
  if (!grouped) return <div style={{ display: 'flex', flexDirection: 'column', gap }}>{queues.map((q) => <AcRow key={q.id} q={q} big={big} mark />)}</div>;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap }}>
      {[a.anna, a.rock, jf].map((acc) => {
        const rows = queues.filter((q) => q.acc.id === acc.id);
        if (!rows.length) return null;
        return (
          <React.Fragment key={acc.id}>
            <div style={{ display: 'flex', alignItems: 'center' }}>
              <AcHeader acc={acc} big={big} style={{ flex: 1 }} />
              {acc.id === 'jf' && <ProviderBadge provider="jellyfin" size={big ? 24 : 18} style={{ marginRight: big ? 20 : 14 }} />}
            </div>
            {rows.map((q) => <AcRow key={q.id} q={q} big={big} />)}
          </React.Fragment>
        );
      })}
    </div>
  );
}
// Status line under the server address while it is checked (D78: the server is identified before signing in).
function JfServerStatus({ state }) {
  const t = useT(); const jf = useJfAccount();
  const rows = {
    checking: [<SiSpinner size={18} key="s" />, t('Checking the server…', 'Проверяем сервер…'), HF.text2],
    found: [<Icon name="check_circle" size={18} color={HF.success} key="i" />, `${jf.name} · ${jf.version}`, HF.text],
    unreachable: [<Icon name="error" size={18} color={HF.danger} key="i" />, t('Can’t reach the server. Check the address and the connection.', 'Сервер не отвечает. Проверьте адрес и подключение.'), HF.danger],
    notjf: [<Icon name="error" size={18} color={HF.danger} key="i" />, t('This address answers, but it isn’t a Jellyfin server.', 'По этому адресу кто-то отвечает, но это не сервер Jellyfin.'), HF.danger],
  }[state];
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, margin: '10px 4px 0' }}>
      {rows[0]}<Txt t="caption" color={rows[2]} style={{ flex: 1 }}>{rows[1]}</Txt>
    </div>
  );
}

// ── Section 9 · accounts and sign-in (phone) ────────────────────────────────
// Settings → Accounts → "Add account": the provider chooser (Yandex Music / Jellyfin).
function JfAddChooser() {
  const t = useT(); const w = useSiWords();
  const row = (provider, title, sub) => (
    <div style={{ display: 'flex', alignItems: 'center', gap: 14, height: 76, borderTop: provider === 'jellyfin' ? `1px solid ${HF.hairline}` : 'none' }}>
      <ProviderBadge provider={provider} size={40} />
      <div style={{ flex: 1, minWidth: 0 }}><Txt t="rowTitle" ellipsis>{title}</Txt><Txt t="caption" color={HF.text2} ellipsis>{sub}</Txt></div>
      <Icon name="chevron_right" size={22} color={HF.text3} />
    </div>
  );
  return (
    <Frame kind="phone">
      <AcSettings multi />
      <Scrim opacity={0.55} />
      <Sheet height={330}>
        <div style={{ padding: '16px 20px 0' }}>
          <Txt t="dialogTitle">{t('Add account', 'Добавить аккаунт')}</Txt>
          <Txt t="caption" color={HF.text2} style={{ margin: '4px 0 8px' }}>{t('Where is the music?', 'Откуда музыка?')}</Txt>
          {row('yandex', w.yandex, t('Another Yandex account — its own My Wave and likes', 'Ещё один аккаунт Яндекса — своя «Моя волна» и лайки'))}
          {row('jellyfin', 'Jellyfin', t('Your own music server — address, then a code or a password', 'Свой музыкальный сервер — адрес, затем код или пароль'))}
          <Txt t="caption" color={HF.text3} style={{ marginTop: 10 }}>
            {t('Every account works at the same time and gets its own queue to start from.', 'Все аккаунты работают одновременно, у каждого — своя очередь для начала.')}
          </Txt>
        </div>
      </Sheet>
    </Frame>
  );
}

// The address step with its check. state: checking | found | unreachable | notjf
function JfAddress({ state }) {
  const t = useT(); const w = useSiWords();
  const bad = state === 'unreachable' || state === 'notjf';
  return (
    <SiSheetPage title="Jellyfin" badge={<ProviderBadge provider="jellyfin" size={32} />}>
      <Txt t="body" color={HF.text2} style={{ padding: '0 4px' }}>{w.intro}</Txt>
      <SiField label={w.server} icon="dns" value={SI_SERVER} error={bad} style={{ marginTop: 16 }}
        trailing={state === 'found' ? <Icon name="check" size={22} color={HF.success} /> : null} />
      <JfServerStatus state={state} />
      {state === 'found' && (
        <React.Fragment>
          <SiWays value="qc" style={{ marginTop: 18 }} />
          <Txt t="caption" color={HF.text3} style={{ margin: '10px 4px 0' }}>
            {t('Quick Connect is on for this server — no password to type.', 'На этом сервере включено быстрое подключение — пароль вводить не нужно.')}
          </Txt>
          <MetalPill style={{ marginTop: 22 }}>{t('Get a code', 'Получить код')}</MetalPill>
        </React.Fragment>
      )}
      {state === 'checking' && <TextAction color={HF.text2} style={{ justifyContent: 'center', marginTop: 18 }}>{w.cancel}</TextAction>}
      {bad && <MetalPill icon="refresh" style={{ marginTop: 22 }}>{t('Check again', 'Проверить снова')}</MetalPill>}
      {state === 'notjf' && (
        <Txt t="caption" color={HF.text3} style={{ margin: '12px 4px 0' }}>
          {t('Use the address you open Jellyfin with in a browser, e.g. https://music.example.org.', 'Укажите адрес, по которому Jellyfin открывается в браузере, например https://music.example.org.')}
        </Txt>
      )}
    </SiSheetPage>
  );
}

// Quick Connect confirmed in Jellyfin: the app signs in by itself.
function JfQuickApproved() {
  const t = useT(); const w = useSiWords();
  return (
    <SiSheetPage title="Jellyfin" badge={<ProviderBadge provider="jellyfin" size={32} />}>
      <SiField label={w.server} icon="dns" value={SI_SERVER} trailing={<Icon name="check" size={22} color={HF.success} />} style={{ marginTop: 4 }} />
      <JfServerStatus state="found" />
      <SiWays value="qc" style={{ marginTop: 18 }} />
      <SiCode style={{ marginTop: 22, padding: '0 2px', opacity: 0.5 }} />
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, margin: '18px 4px 0' }}>
        <Icon name="check_circle" size={22} color={HF.success} />
        <Txt t="rowTitle">{t('Confirmed in Jellyfin', 'Подтверждено в Jellyfin')}</Txt>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, margin: '12px 4px 0' }}>
        <SiSpinner /><Txt t="body" color={HF.text2}>{t('Signing in as alex…', 'Входим как alex…')}</Txt>
      </div>
    </SiSheetPage>
  );
}

// The same server + user is already signed in: refused (D78), nothing is created twice.
function JfDuplicate() {
  const t = useT(); const w = useSiWords(); const jf = useJfAccount();
  return (
    <Frame kind="phone">
      <SiSignIn state="filled" />
      <Scrim opacity={0.45} />
      <Dialog width={340} title={t('Already added', 'Уже добавлен')}
        text={t(`alex on ${jf.name} is already in your accounts. One server and user is one account — its queues and library are already here.`,
          `alex на сервере ${jf.name} уже есть в аккаунтах. Один сервер и пользователь — один аккаунт: его очереди и музыка уже здесь.`)}
        actions={<React.Fragment><DialogAction>{w.cancel}</DialogAction><DialogAction kind="primary">{t('Show account', 'Показать аккаунт')}</DialogAction></React.Fragment>} />
    </Frame>
  );
}

// Settings → Accounts with Yandex and Jellyfin side by side; "Group" because there is more than one account.
function JfAccounts() {
  const t = useT(); const a = useAcAccounts(); const jf = useJfAccount(); const w = useSiWords();
  const accRow = (acc, sub, i, badge) => (
    <div key={acc.id} style={{ display: 'flex', alignItems: 'center', gap: 14, height: 72, borderTop: i ? `1px solid ${HF.hairline}` : 'none' }}>
      <div style={{ position: 'relative' }}>
        <AcMark acc={acc} size={40} />
        {badge && <ProviderBadge provider="jellyfin" size={16} style={{ position: 'absolute', right: -4, bottom: -4, boxShadow: `0 0 0 2px ${HF.sheet}` }} />}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}><Txt t="rowTitle" ellipsis>{acc.name}</Txt><Txt t="caption" color={HF.text2} ellipsis>{sub}</Txt></div>
      <Icon name="more_vert" size={22} color={HF.text2} />
    </div>
  );
  return (
    <SiSheetPage title={t('Accounts', 'Аккаунты')}>
      <Txt t="body" color={HF.text2} style={{ padding: '0 4px' }}>
        {t('All accounts work at the same time. Each queue belongs to the account it was created on.', 'Все аккаунты работают одновременно. Каждая очередь принадлежит аккаунту, в котором создана.')}
      </Txt>
      <SectionCaps style={{ margin: '18px 4px 10px' }}>{w.yandex}</SectionCaps>
      <SiCard>{[a.anna, a.rock].map((acc, i) => accRow(acc, acc.sub, i))}</SiCard>
      <SectionCaps style={{ margin: '18px 4px 10px' }}>Jellyfin</SectionCaps>
      <SiCard>
        {accRow(jf, jf.sub, 0, true)}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, height: 44, borderTop: `1px solid ${HF.hairline}` }}>
          <Icon name="dns" size={20} color={HF.text3} style={{ width: 40, textAlign: 'center' }} />
          <Txt t="caption" color={HF.text2} style={{ flex: 1 }}>{jf.version}</Txt>
          <Icon name="check_circle" size={16} color={HF.success} /><Txt t="caption" color={HF.text2}>{t('reachable', 'на связи')}</Txt>
        </div>
      </SiCard>
      <SiCard style={{ marginTop: 10 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, height: 60 }}>
          <Icon name="person_add" size={24} color={HF.text} style={{ width: 40, textAlign: 'center' }} />
          <Txt t="rowTitle">{t('Add account', 'Добавить аккаунт')}</Txt>
        </div>
      </SiCard>
      <SiCard style={{ marginTop: 10 }}>
        <SiSetRow first icon="account_tree" title={t('Group', 'Группировать')} sub={t('Off — one list, a marker on each row', 'Выкл. — один список с метками')} trailing={<Switch on />} />
      </SiCard>
    </SiSheetPage>
  );
}

// ── Section 4 · queues (phone) ───────────────────────────────────────────────
// The switcher with a Jellyfin account: its default queue (D83) is there from sign-in.
function JfPhoneSwitcher({ grouped }) {
  const t = useT(); const queues = useJfQueues();
  return (
    <Frame kind="phone">
      <PhoneHome dim />
      <Sheet height={860}>
        <QueueSheetHeader title={t('Queues', 'Очереди')} subtitle={t('Tap to switch · hold for more', 'Нажмите, чтобы переключить · удерживайте для действий')} />
        <div style={{ padding: '0 8px' }}><JfList queues={queues} grouped={grouped} /></div>
        <div style={{ position: 'absolute', left: 20, right: 20, bottom: 24 }}><GlassPill icon="add">{t('New queue', 'Новая очередь')}</GlassPill></div>
      </Sheet>
    </Frame>
  );
}

// "Add to queue…" for a Jellyfin track: only the queues of the track's account (D84).
function JfPhoneAddToQueue() {
  const t = useT(); const jf = useJfAccount();
  const queues = useJfQueues().filter((q) => q.acc.id === 'jf').map((q) => ({ ...q, active: false }));
  return (
    <Frame kind="phone">
      <Scrim opacity={0.2} />
      <Sheet height={520}>
        <QueueSheetHeader title={t('Add to queue', 'Добавить в очередь')} subtitle="Neon Harbour · Lumen Lane" />
        <div style={{ padding: '0 8px' }}>
          <div style={{ display: 'flex', alignItems: 'center' }}>
            <AcHeader acc={jf} style={{ flex: 1 }} /><ProviderBadge provider="jellyfin" size={18} style={{ marginRight: 14 }} />
          </div>
          {queues.map((q) => <AcRow key={q.id} q={q} />)}
        </div>
        <Txt t="caption" color={HF.text3} style={{ margin: '12px 20px 0' }}>
          {t(`Only ${jf.name} queues: a queue holds music of its own account.`, `Только очереди ${jf.name}: в очереди — музыка её аккаунта.`)}
        </Txt>
        <div style={{ position: 'absolute', left: 20, right: 20, bottom: 24 }}><GlassPill icon="add">{t(`New queue on ${jf.name}`, `Новая очередь в ${jf.name}`)}</GlassPill></div>
      </Sheet>
    </Frame>
  );
}

// ── Section 11b · album and playlist pages (phone) ──────────────────────────
function JfPageHead({ title, sub, hue, round }) {
  return (
    <div style={{ display: 'flex', gap: 14, alignItems: 'center', marginBottom: 16 }}>
      <Cover size={104} hue={hue} radius={round ? 52 : 16} />
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: 24, lineHeight: '29px', fontWeight: 800 }}>{title}</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 6 }}><ProviderBadge provider="jellyfin" size={16} /><Txt t="caption" color={HF.text2}>{sub}</Txt></div>
      </div>
    </div>
  );
}
function JfPageActions() {
  const t = useT();
  return (
    <React.Fragment>
      <div style={{ display: 'flex', gap: 10 }}>
        <MetalPill icon="play_arrow" style={{ flex: 1 }}>{t('Play', 'Слушать')}</MetalPill>
        <GlassPill icon="shuffle" style={{ flex: 1 }}>{t('Shuffle', 'Перемешать')}</GlassPill>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
        <GlassPill icon="stacks" height={40} style={{ flex: 1 }}>{t('Add to queue', 'В очередь')}</GlassPill>
        <GlassPill icon="download" height={40} style={{ flex: 1 }}>{t('Download', 'Скачать')}</GlassPill>
        <GlassCircle icon="more_horiz" size={40} />
      </div>
    </React.Fragment>
  );
}
function JfAlbumPage() {
  const t = useT();
  return (
    <Frame kind="phone" tint="#5A3E8C">
      <div style={{ position: 'absolute', left: 16, right: 16, top: 22, display: 'flex', gap: 12 }}><GlassCircle icon="arrow_back" /></div>
      <div style={{ position: 'absolute', left: 16, right: 16, top: 84 }}>
        <JfPageHead title="Night Drive" sub={t('Lumen Lane · 2021 · 6 tracks · 27:41', 'Lumen Lane · 2021 · 6 треков · 27:41')} hue="violet" />
        <JfPageActions />
        <SectionCaps style={{ margin: '20px 4px 4px' }}>{t('Tracks', 'Треки')}</SectionCaps>
        <div style={{ padding: '0 4px' }}>
          {JF_ALBUM.map(([title, d], i) => (
            <div key={title} style={{ display: 'flex', alignItems: 'center', gap: 12, height: 52, borderBottom: i === JF_ALBUM.length - 1 ? 'none' : `1px solid ${HF.hairline}` }}>
              <Txt t="body" color={HF.text3} style={{ width: 22, textAlign: 'right' }}>{i + 1}</Txt>
              <Txt t="rowTitle" ellipsis style={{ flex: 1 }}>{title}</Txt>
              <Txt t="caption" color={HF.text3}>{d}</Txt><Icon name="more_vert" size={22} color={HF.text2} />
            </div>
          ))}
        </div>
      </div>
      <PhoneChrome active="library" title="Paper Lanterns" artist="The Quiet Coast" hue="teal" />
    </Frame>
  );
}
function JfPlaylistPage() {
  const t = useT();
  return (
    <Frame kind="phone" tint="#2E5E6B">
      <div style={{ position: 'absolute', left: 16, right: 16, top: 22, display: 'flex', gap: 12 }}><GlassCircle icon="arrow_back" /></div>
      <div style={{ position: 'absolute', left: 16, right: 16, top: 84 }}>
        <JfPageHead title={t('Evening mix', 'Вечерний микс')} sub={t('Playlist · 22 tracks · 1 h 24 min', 'Плейлист · 22 трека · 1 ч 24 мин')} hue="teal" />
        <JfPageActions />
        <div style={{ marginTop: 14 }}>
          {JF_PLAYLIST.slice(0, 5).map(([title, artist, hue, d]) => <TrackRow key={title} title={title} artist={artist} hue={hue} duration={d} />)}
        </div>
      </div>
      <PhoneChrome active="library" title="Paper Lanterns" artist="The Quiet Coast" hue="teal" />
    </Frame>
  );
}

// ── Section 13 · server unreachable on a Jellyfin page ───────────────────────
// What was loaded before stays visible from the cache; the page says the server is away and retries by itself.
function JfUnreachable() {
  const t = useT(); const w = useSiWords(); const jf = useJfAccount();
  return (
    <Frame kind="phone" tint="#5A3E8C">
      <div style={{ position: 'absolute', left: 16, right: 16, top: 22, display: 'flex', gap: 12 }}><GlassCircle icon="arrow_back" /></div>
      <div style={{ position: 'absolute', left: 16, right: 16, top: 84 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, borderRadius: 16, padding: '12px 14px', marginBottom: 16,
          background: 'rgba(255,107,107,0.10)', border: `1px solid rgba(255,107,107,0.35)` }}>
          <Icon name="cloud_off" size={22} color={HF.danger} />
          <Txt t="caption" color={HF.text} style={{ flex: 1 }}>
            {t(`${jf.name} isn’t reachable. Shown from the last visit; tracks on the device still play. Retrying by itself.`,
              `${jf.name} не отвечает. Показано с прошлого раза; скачанное играет. Повторим сами.`)}
          </Txt>
          <TextAction>{w.retry}</TextAction>
        </div>
        <JfPageHead title="Night Drive" sub={t('Lumen Lane · 2021 · 6 tracks', 'Lumen Lane · 2021 · 6 треков')} hue="violet" />
        <div style={{ padding: '0 4px' }}>
          {JF_ALBUM.map(([title, d], i) => (
            <div key={title} style={{ display: 'flex', alignItems: 'center', gap: 12, height: 52, opacity: i === 0 || i === 3 ? 1 : 0.42,
              borderBottom: i === JF_ALBUM.length - 1 ? 'none' : `1px solid ${HF.hairline}` }}>
              <Txt t="body" color={HF.text3} style={{ width: 22, textAlign: 'right' }}>{i + 1}</Txt>
              <Txt t="rowTitle" ellipsis style={{ flex: 1 }}>{title}</Txt>
              {(i === 0 || i === 3) && <Icon name="download_done" size={18} color={HF.success} />}
              <Txt t="caption" color={HF.text3}>{d}</Txt>
            </div>
          ))}
        </div>
      </div>
      <PhoneChrome active="library" title="Night Drive" artist="Lumen Lane" hue="violet" />
    </Frame>
  );
}

// ── Section 5 · head unit ────────────────────────────────────────────────────
function JfCarFar() {
  const t = useT();
  return (
    <React.Fragment>
      <div style={{ position: 'absolute', left: 980, top: 96 }}><Cover size={320} hue="violet" radius={36} style={{ boxShadow: '0 0 90px rgba(150,110,230,0.45)' }} /></div>
      <div style={{ position: 'absolute', left: 1360, top: 110, width: 500 }}>
        <div style={{ ...HFType.sectionCaps, fontSize: 15, color: HF.text2, marginBottom: 12 }}>{t('Next', 'Далее')}</div>
        {JF_PLAYLIST.slice(2, 5).map(([a, b, hue]) => (
          <div key={a} style={{ display: 'flex', alignItems: 'center', gap: 16, height: 92 }}>
            <Cover size={72} hue={hue} radius={16} />
            <div style={{ minWidth: 0 }}><div className="hf-ellipsis" style={{ fontSize: 24, fontWeight: 700 }}>{a}</div><div style={{ fontSize: 18, color: HF.text2 }}>{b}</div></div>
          </div>
        ))}
      </div>
    </React.Fragment>
  );
}
function JfCarHome({ dim }) {
  const t = useT(); const jf = useJfAccount();
  return <CarHome dim={dim} pillLead={<AcMark acc={jf} size={30} />} pillName={jf.name} track={['Night Drive', 'Lumen Lane', 'violet']} far={<JfCarFar />}
    queueLine={t('Queue · 4 of 37', 'Очередь · 4 из 37')} />;
}
function JfCarPanel({ grouped }) {
  const t = useT();
  const queues = useJfQueues().filter((q) => ['a-wave', 'a-family', 'r-wave', 'j-home', 'j-calm'].includes(q.id));
  return (
    <Frame kind="head" tint="#5A3E8C" glowY="40%">
      <JfCarHome dim />
      <SidePanel width={720} side="left">
        <QueueSheetHeader big title={t('Queues', 'Очереди')} trailing={<GlassCircle icon="add" size={60} />} />
        <div style={{ padding: '0 14px' }}><JfList queues={queues} grouped={grouped} big /></div>
      </SidePanel>
    </Frame>
  );
}

// ── Boards, keyed by the section they join (`after` = the board they follow) ─
function jellyfinBoards() {
  const P = FRAMES.phone, H = FRAMES.head;
  return {
    'si-signin': [
      { after: 'ac-settings-multi', boards: [
        { id: 'jf-add', label: 'Phase 4 · Accounts → Add account · provider chooser: Yandex Music / Jellyfin', w: P.w, h: P.h, el: <JfAddChooser /> },
        { id: 'jf-addr-checking', label: 'Jellyfin · server address checked first · checking', w: P.w, h: P.h, el: <JfAddress state="checking" /> },
        { id: 'jf-addr-found', label: 'Jellyfin · server found: its name and version, then the way to sign in (Quick Connect suggested when on)', w: P.w, h: P.h, el: <JfAddress state="found" /> },
        { id: 'jf-addr-unreachable', label: 'Jellyfin · server unreachable — Check again', w: P.w, h: P.h, el: <JfAddress state="unreachable" /> },
        { id: 'jf-addr-notjf', label: 'Jellyfin · the address answers but isn’t Jellyfin', w: P.w, h: P.h, el: <JfAddress state="notjf" /> },
      ] },
      { after: 'si-qc-off', boards: [
        { id: 'jf-qc-approved', label: 'Quick Connect · confirmed in Jellyfin → signing in by itself', w: P.w, h: P.h, el: <JfQuickApproved /> },
        { id: 'jf-duplicate', label: 'Same server + user already signed in → refused, nothing created twice (D78)', w: P.w, h: P.h, el: <JfDuplicate /> },
        { id: 'jf-accounts', label: 'Settings → Accounts · two Yandex accounts + a Jellyfin account (server name, version, reachable); "Group"', w: P.w, h: P.h, el: <JfAccounts /> },
      ] },
    ],
    'queues-phone': [
      { after: 'ac-sheet-removed', boards: [
        { id: 'jf-sheet-grouped', label: 'Queues · with a Jellyfin account, grouped · its default queue from sign-in (D83)', w: P.w, h: P.h, el: <JfPhoneSwitcher grouped /> },
        { id: 'jf-sheet-flat', label: 'Queues · with a Jellyfin account, flat · markers on rows', w: P.w, h: P.h, el: <JfPhoneSwitcher /> },
      ] },
      { after: 'ac-add-flat', boards: [
        { id: 'jf-add-to-queue', label: 'Add to queue… on a Jellyfin track · only that account’s queues (D84)', w: P.w, h: P.h, el: <JfPhoneAddToQueue /> },
      ] },
    ],
    'queues-car': [
      { after: 'acc-panel-flat', boards: [
        { id: 'jfc-home', label: 'Car mode · a Jellyfin queue playing · its marker in the pill', w: H.w, h: H.h, el: <Frame kind="head" tint="#5A3E8C" glowY="40%"><JfCarHome /></Frame> },
        { id: 'jfc-panel', label: 'Car mode · queues panel with the Jellyfin account, grouped', w: H.w, h: H.h, el: <JfCarPanel grouped /> },
        { id: 'jfc-panel-flat', label: 'Car mode · queues panel with the Jellyfin account, flat', w: H.w, h: H.h, el: <JfCarPanel /> },
      ] },
    ],
    'home-library': [
      { after: 'hm-lib-liked-c', boards: [
        { id: 'jf-album', label: 'Jellyfin album page · Play / Shuffle / Add to queue / Download', w: P.w, h: P.h, el: <JfAlbumPage /> },
        { id: 'jf-playlist', label: 'Jellyfin playlist page', w: P.w, h: P.h, el: <JfPlaylistPage /> },
      ] },
    ],
    'si-states': [
      { after: 'si-list-error', boards: [
        { id: 'jf-unreachable', label: 'Jellyfin page while the server is unreachable · last visit shown, downloaded tracks play, retries by itself', w: P.w, h: P.h, el: <JfUnreachable /> },
      ] },
    ],
  };
}

Object.assign(window, { useJfAccount, useJfQueues, JfList, JfServerStatus, JfAddChooser, JfAddress, JfQuickApproved, JfDuplicate, JfAccounts,
  JfPhoneSwitcher, JfPhoneAddToQueue, JfAlbumPage, JfPlaylistPage, JfUnreachable, JfCarHome, JfCarPanel, jellyfinBoards });
