// homejf.jsx — (e) Home and Library with Jellyfin (tasks/todo.md "gaps after v0.5.0", 2026-11-19).
// Refreshes the 11a / 11b proposals (home.jsx) to the new main screen (player.jsx `PlMainContent`: the queue
// dropdown on top, no queue button, no "Player" link) and to what Jellyfin offers now.
// Home: the hero stays first (My Wave with Yandex — Yandex only is pl-main unchanged; "Continue" without it). Each
// signed-in Jellyfin account adds a group below it — Pinned folders · Recently added · Favourites — so Home scrolls;
// once the hero is scrolled off the title collapses and the mini player returns.
// Library (user 2026-11-19): one section per account — Liked / Favourites · Playlists · Albums · Artists ·
// Downloaded · Folders (Jellyfin) — each with its own counts, no badges; a row opens that account's list. The
// merged rows with Y / J badges (hj-lib-c) were rejected. The Downloaded row stays, per account (covers Jellyfin
// downloads too); it replaces D33's "Settings → Storage only".
// Every top-level name here starts with `Hj` (the canvas scripts share one global scope).

const HJ_FAV = [['Paper Lanterns', 'The Quiet Coast', 'teal'], ['Night Drive', 'Lumen Lane', 'violet'], ['Copper Sky', 'Hollow Pines', 'amber']];
const HJ_NEW = [['Night Drive', 'Lumen Lane', 'violet'], ['Field Notes', 'Field Notes', 'blue'], ['Copper Sky', 'Hollow Pines', 'amber'], ['Low Orbit', 'Static Bloom', 'rose']];

// ── Home sections of one Jellyfin account ────────────────────────────────────
function HjGroupHead({ badge, style }) {
  const jf = useJfAccount();
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, height: 44, ...style }}>
      <AcMark acc={jf} size={24} />
      <div style={{ fontSize: 19, fontWeight: 800 }}>{jf.name}</div>
      {badge && <ProviderBadge provider="jellyfin" size={16} />}
      <div style={{ flex: 1, height: 1, background: HF.hairline, marginLeft: 6 }} />
    </div>
  );
}
function HjSections({ badge, head = true }) {
  const t = useT(); const w = useFolderWords();
  return (
    <div>
      {head && <HjGroupHead badge={badge} style={{ margin: '0 20px' }} />}
      <HmCaps style={{ margin: '0 12px 0 20px' }} trailing={<HmMore>{w.all}</HmMore>}>{t('Pinned folders', 'Закреплённые папки')}</HmCaps>
      <div style={{ display: 'flex', gap: 12, paddingLeft: 20, overflow: 'hidden' }}>
        {FD_PINNED.concat(['Детские']).map((n) => (
          <div key={n} style={{ width: 112, flex: '0 0 auto' }}>
            <div style={{ position: 'relative' }}>
              <FdArt name={n} art={FD_ART[n] || null} size={112} radius={18} />
              <GlassCircle icon="play_arrow" size={40} style={{ position: 'absolute', right: 6, bottom: 6, background: HF.barFill }} />
            </div>
            <Txt t="cardTitle" ellipsis style={{ marginTop: 8 }}>{n}</Txt>
          </div>
        ))}
      </div>
      <HmCaps style={{ margin: '10px 12px 0 20px' }} trailing={<HmMore>{t('All', 'Все')}</HmMore>}>{t('Recently added', 'Недавно добавленные')}</HmCaps>
      <div style={{ display: 'flex', gap: 12, paddingLeft: 20, overflow: 'hidden' }}>
        {HJ_NEW.map(([a, b, hue]) => <HmTile key={a} size={112} hue={hue} title={a} sub={b} />)}
      </div>
      <HmCaps style={{ margin: '10px 12px 0 20px' }} trailing={<HmMore>{t('All · 87', 'Все · 87')}</HmMore>}>{t('Favourites', 'Избранное')}</HmCaps>
      <div style={{ margin: '0 16px', borderRadius: 22, background: HF.glassFill, border: `1px solid ${HF.glassBorder}`, padding: '4px 12px 4px' }}>
        {HJ_FAV.map(([a, b, hue]) => <TrackRow key={a} title={a} artist={b} hue={hue} trailing={<Icon name="favorite" size={20} color={HF.love} />} />)}
        <div style={{ display: 'flex', gap: 10, padding: '6px 0 8px' }}>
          <MetalPill icon="play_arrow" height={44} style={{ flex: 1 }}>{t('Play', 'Слушать')}</MetalPill>
          <GlassPill icon="shuffle" height={44} style={{ flex: 1 }}>{t('Shuffle', 'Перемешать')}</GlassPill>
        </div>
      </div>
    </div>
  );
}

// (a) Yandex only: pl-main as is.
function HjHomeA() {
  return <Frame kind="phone" tint="#2C4FA8" glowY="26%"><PlMainContent /><PlTabBar /></Frame>;
}
// (c) Both, first screen: the hero is today's; the Jellyfin group starts under "Next in the wave".
function HjHomeC() {
  const t = useT();
  return (
    <Frame kind="phone" tint="#2C4FA8" glowY="26%">
      <PlMainContent />
      <div style={{ position: 'absolute', left: 0, right: 0, top: 772, height: 56, overflow: 'hidden' }}><HjGroupHead badge style={{ margin: '0 20px' }} /></div>
      <PlNote icon="swipe_up" style={{ position: 'absolute', right: 16, top: 734 }}>{t('Home scrolls', 'Главная прокручивается')}</PlNote>
      <PlTabBar />
    </Frame>
  );
}
// (c) Both, scrolled: the title collapses to one row (the queue dropdown stays), the mini player returns.
function HjHomeCScroll() {
  const t = useT();
  const fade = 'linear-gradient(180deg, transparent 0, #000 30px, #000 calc(100% - 30px), transparent 100%)';
  return (
    <Frame kind="phone" tint="#2C4FA8" glowY="0%">
      <div style={{ position: 'absolute', left: 0, right: 0, top: 70, bottom: 156, overflow: 'hidden', WebkitMaskImage: fade, maskImage: fade }}>
        <div style={{ position: 'absolute', left: 0, right: 0, top: 14 }}><HjSections badge /></div>
      </div>
      <div style={{ position: 'absolute', left: 20, right: 16, top: 16, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ fontSize: 24, lineHeight: '30px', fontWeight: 700 }}>{t('My Wave', 'Моя волна')}</div>
        <PlDropdown />
      </div>
      <HmPhoneChrome sit={HM_SIT.c} active="home" mini="wave" />
    </Frame>
  );
}
// (c) Both, the whole page: where each screenful ends.
function HjHomeCFull() {
  const t = useT();
  const fold = (y, text) => (
    <div style={{ position: 'absolute', left: 0, right: 0, top: y, borderTop: `2px dashed ${HF.accent}` }}>
      <div style={{ position: 'absolute', right: 8, top: 4, fontSize: 12, fontWeight: 700, color: HF.accent }}>{text}</div>
    </div>
  );
  return (
    <div className="hf" style={{ position: 'relative', width: 412, height: 1500, overflow: 'hidden', background: HF.ground }}>
      <Backdrop tint="#2C4FA8" glowY="8%">
        <div style={{ position: 'absolute', left: 0, right: 0, top: 0, height: 915 }}><PlMainContent /></div>
        <div style={{ position: 'absolute', left: 0, right: 0, top: 772 }}><HjSections badge /></div>
        {fold(833, t('screen 1 ends (tab bar)', 'конец 1-го экрана (вкладки)'))}
        {fold(1398, t('end of Home', 'конец Главной'))}
        <div style={{ position: 'absolute', left: 16, right: 16, top: 1410 }}><HmTabBar sit={HM_SIT.c} active="home" /></div>
      </Backdrop>
    </div>
  );
}
// (b) Jellyfin only: "Continue" is the hero, the queue dropdown on top; no group header (one account).
function HjHomeB() {
  const t = useT(); const jf = useJfAccount();
  const q = { name: 'Home Media', track: 'Night Drive', artist: 'Lumen Lane', hue: 'violet', pos: 4, total: 37, progress: 0.38 };
  return (
    <Frame kind="phone" tint="#5A3E8C" glowY="18%">
      <div style={{ position: 'absolute', left: 20, right: 16, top: 18, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <LargeTitle>{t('Home', 'Главная')}</LargeTitle>
        <PlDropdown name={jf.name} lead={<AcMark acc={jf} size={22} />} />
      </div>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 76, bottom: 98, overflow: 'hidden', WebkitMaskImage: HM_FADE, maskImage: HM_FADE }}>
        <div style={{ margin: '0 16px 14px' }}><HmContinueCard q={q} /></div>
        <HjSections head={false} />
      </div>
      <div style={{ position: 'absolute', left: 16, right: 16, bottom: 16 }}><HmTabBar sit={HM_SIT.b} active="home" /></div>
    </Frame>
  );
}

// ── Library ──────────────────────────────────────────────────────────────────
// counts: a number (one service) or [[provider, n], …] (both: a badge before each count).
function HjLibRow({ icon, title, counts, last }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 14, height: 60 }}>
      <IconPlaque icon={icon} />
      <div style={{ flex: 1, minWidth: 0, alignSelf: 'stretch', display: 'flex', alignItems: 'center', gap: 10, borderBottom: last ? 'none' : `1px solid ${HF.hairline}` }}>
        <Txt t="rowTitle" ellipsis style={{ flex: 1 }}>{title}</Txt>
        {Array.isArray(counts)
          ? counts.map(([p, n]) => <div key={p} style={{ display: 'flex', alignItems: 'center', gap: 4 }}><ProviderBadge provider={p} size={15} /><Txt t="body" color={HF.text3}>{n}</Txt></div>)
          : <Txt t="body" color={HF.text3}>{counts}</Txt>}
        <Icon name="chevron_right" size={22} color={HF.text3} />
      </div>
    </div>
  );
}
const HJ_N = { liked: [312, 87], playlists: [6, 9], albums: [140, 486], artists: [58, 212], downloaded: [214, 64], folders: [0, 34] };
function HjUseRows(sit) {
  const t = useT();
  const both = sit.y && sit.j;
  const c = (k) => both ? [['yandex', HJ_N[k][0]], ['jellyfin', HJ_N[k][1]]].filter(([, n]) => n) : sit.y ? HJ_N[k][0] : HJ_N[k][1];
  const liked = both ? t('Liked & favourites', 'Любимое') : sit.y ? t('Liked', 'Мне нравится') : t('Favourites', 'Избранное');
  return [
    { id: 'liked', icon: 'favorite', title: liked, counts: c('liked') },
    { id: 'playlists', icon: 'playlist_play', title: t('Playlists', 'Плейлисты'), counts: c('playlists') },
    { id: 'albums', icon: 'album', title: t('Albums', 'Альбомы'), counts: c('albums') },
    { id: 'artists', icon: 'artist', title: t('Artists', 'Исполнители'), counts: c('artists') },
    { id: 'downloaded', icon: 'download_done', title: t('Downloaded', 'Скачанное'), counts: c('downloaded') },
    sit.j && { id: 'folders', icon: 'folder', title: t('Folders', 'Папки'), counts: c('folders') },
  ].filter(Boolean);
}
function HjLibrary({ sit, mini }) {
  const t = useT(); const rows = HjUseRows(sit); const both = sit.y && sit.j;
  const recent = sit.j ? HJ_NEW : [[t('First song', 'Первая песня'), t('The Band', 'Группа'), 'blue'], ['A very long song title', 'Artist with a long name', 'rose']];
  return (
    <Frame kind="phone">
      <div style={HM_PHONE_SCROLL}>
        <HmLibraryTop first={both ? 'yandex' : undefined} />
        <div style={{ marginTop: both ? 8 : 14 }}>{rows.map((r, i) => <HjLibRow key={r.id} {...r} last={i === rows.length - 1} />)}</div>
        {both && (
          <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start', marginTop: 10, padding: '10px 12px', borderRadius: 14, background: HF.glassFill, border: `1px dashed ${HF.glassBorder}` }}>
            <Icon name="lightbulb" size={18} color={HF.text2} />
            <Txt t="caption" color={HF.text2}>{t('Not taken: the per-account sections (hj-lib) were chosen instead.', 'Не принято: выбраны разделы по аккаунтам (hj-lib).')}</Txt>
          </div>
        )}
        <HmCaps style={{ marginTop: 10 }}>{t('Recently added', 'Недавно добавленные')}</HmCaps>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px 12px', marginTop: 2 }}>
          {recent.slice(0, 2).map(([a, b, hue]) => <HmTile key={a} size={180} hue={hue} title={a} sub={b} />)}
        </div>
      </div>
      <HmPhoneChrome sit={sit} active="library" mini={mini} />
    </Frame>
  );
}
// The chosen Library (user 2026-11-19): one section per account, in Settings → Accounts order, each with its own rows
// and plain counts (no badges, nothing summed): Liked / Favourites · Playlists · Albums · Artists · Downloaded ·
// Folders (Jellyfin). A row opens that account's list directly (no service pill). The Downloaded row stays, per
// account (revises D33's "no Downloaded row"). With one account the header stays too: it names where the rows
// come from, so every situation looks the same (an exception to D58's "no header with one account").
function HjUseLibAccounts(sit) {
  const t = useT(); const jf = useJfAccount(); const a = useAcAccounts();
  return [
    sit.y && { acc: a.anna, sub: t('Yandex Music', 'Яндекс Музыка'), sit: { y: true, j: false } },
    sit.j && { acc: jf, sub: 'Jellyfin', sit: { y: false, j: true } },
  ].filter(Boolean);
}
function HjAccHead({ acc, sub, style }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, height: 40, ...style }}>
      <AcMark acc={acc} size={22} /><div style={{ fontSize: 17, fontWeight: 800 }}>{acc.name}</div>
      <Txt t="caption" color={HF.text3}>· {sub}</Txt>
      <div style={{ flex: 1, height: 1, background: HF.hairline, marginLeft: 4 }} />
    </div>
  );
}
function HjAccSection({ acc, sub, sit, style }) {
  const rows = HjUseRows(sit);
  return (
    <div style={style}>
      <HjAccHead acc={acc} sub={sub} />
      {rows.map((r, i) => <HjLibRow key={r.id} {...r} last={i === rows.length - 1} />)}
    </div>
  );
}
function HjLibraryAcc({ sit, mini }) {
  const t = useT(); const accs = HjUseLibAccounts(sit);
  const recent = sit.j ? HJ_NEW : [[t('First song', 'Первая песня'), t('The Band', 'Группа'), 'blue'], ['A very long song title', 'Artist with a long name', 'rose']];
  return (
    <Frame kind="phone">
      <div style={HM_PHONE_SCROLL}>
        <HmLibraryTop first={accs.length > 1 ? 'yandex' : undefined} />
        {accs.map((x, i) => <HjAccSection key={x.acc.id} {...x} style={{ marginTop: i ? 14 : 10 }} />)}
        {accs.length === 1 && (
          <React.Fragment>
            <HmCaps style={{ marginTop: 10 }}>{t('Recently added', 'Недавно добавленные')}</HmCaps>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px 12px', marginTop: 2 }}>
              {recent.slice(0, 2).map(([a, b, hue]) => <HmTile key={a} size={180} hue={hue} title={a} sub={b} />)}
            </div>
          </React.Fragment>
        )}
      </div>
      <HmPhoneChrome sit={sit} active="library" mini={mini} />
    </Frame>
  );
}

// Phone landscape (960×540 dp drawn at 2× on a 1920×1080 board, as pl-land-*): the accounts side by side, one
// column each; the title row carries Play / Shuffle; tabs and the mini player share one bottom row (the dock).
function HjLand({ children }) {
  const W = 960, H = 540, S = FRAMES.land.w / W;
  return (
    <div style={{ width: FRAMES.land.w, height: FRAMES.land.h, overflow: 'hidden' }}>
      <div style={{ width: W, height: H, transform: `scale(${S})`, transformOrigin: '0 0' }}>{children}</div>
    </div>
  );
}
function HjLibraryLand({ sit = HM_SIT.c, mini = 'queue' }) {
  const t = useT(); const accs = HjUseLibAccounts(sit);
  const fade = 'linear-gradient(180deg, #000 calc(100% - 30px), transparent 100%)';
  return (
    <HjLand>
      <PlFrame w={960} h={540} tint="#2C4FA8" glowY="10%">
        <div style={{ position: 'absolute', left: 24, right: 24, top: 14, display: 'flex', alignItems: 'center', gap: 12 }}>
          <LargeTitle style={{ flex: '0 0 auto' }}>{t('My music', 'Моя музыка')}</LargeTitle>
          <div style={{ flex: 1 }} />
          <MetalPill icon="play_arrow" height={44} style={{ width: 150 }}>{t('Play', 'Слушать')}</MetalPill>
          <GlassPill icon="shuffle" height={44} style={{ width: 170 }}>{t('Shuffle', 'Перемешать')}</GlassPill>
          <GlassCircle icon="settings" />
        </div>
        <div style={{ position: 'absolute', left: 24, right: 24, top: 70, bottom: 92, display: 'flex', gap: 28, overflow: 'hidden', WebkitMaskImage: fade, maskImage: fade }}>
          {accs.map((x) => <HjAccSection key={x.acc.id} {...x} style={{ flex: 1, minWidth: 0 }} />)}
        </div>
        <HmDock sit={sit} active="library" mini={mini} />
      </PlFrame>
    </HjLand>
  );
}
// A merged row opened (Albums): the pill picks the service; hold an album = the album menu.
function HjAlbumsC() {
  const t = useT();
  const list = [['Night Drive', 'Lumen Lane', 'violet'], ['Neon Harbour', 'Lumen Lane', 'blue'], ['Copper Sky', 'Hollow Pines', 'amber'], ['Field Notes', 'Field Notes', 'teal'], ['Low Orbit', 'Static Bloom', 'rose'], ['Morning Tide', 'Field Notes', 'green']];
  return (
    <Frame kind="phone">
      <div style={HM_PHONE_SCROLL}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}><GlassCircle icon="arrow_back" /><GlassCircle icon="search" /></div>
        <LargeTitle style={{ marginTop: 12 }}>{t('Albums', 'Альбомы')}</LargeTitle>
        <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
          <HmProviderPill provider="yandex" name={t('Yandex', 'Яндекс')} count={140} />
          <HmProviderPill provider="jellyfin" name="Home Media" count={486} active />
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px 12px', marginTop: 16 }}>
          {list.map(([a, b, hue]) => <HmTile key={a} size={180} hue={hue} title={a} sub={b} />)}
        </div>
      </div>
      <HmPhoneChrome sit={HM_SIT.c} active="library" mini="queue" />
    </Frame>
  );
}

function homeJfBoards() {
  const P = FRAMES.phone, L = FRAMES.land;
  return {
    'home-home': [
      { after: 'hm-head-home-b', boards: [
        { id: 'hj-home-a', label: '(e) Yandex only · Home = the main screen (pl-main) unchanged, pixel-identical', ...P, el: <HjHomeA /> },
        { id: 'hj-home-c', label: '(e) Both · first screen = pl-main; the Jellyfin account’s group (marker, name, J) starts under "Next in the wave"', ...P, el: <HjHomeC /> },
        { id: 'hj-home-c-scroll', label: '(e) Both · scrolled: title collapses (queue dropdown stays), mini player returns · Pinned folders (play on each), Recently added, Favourites', ...P, el: <HjHomeCScroll /> },
        { id: 'hj-home-c-full', label: '(e) Both · the whole Home page, where each screenful ends · a 2nd Jellyfin account adds a 2nd group (Settings → Accounts order)', w: 412, h: 1500, el: <HjHomeCFull /> },
        { id: 'hj-home-b', label: '(e) Jellyfin only · Home: Continue hero, queue dropdown on top, then the same sections without a group header; scrolls', ...P, el: <HjHomeB /> },
      ] },
    ],
    'home-library': [
      { after: 'am-add-to-playlist', boards: [
        { id: 'hj-lib', label: '(e) Library — CHOSEN · both services: one section per account (marker, name, service), its own rows and counts, no badges · Liked · Playlists · Albums · Artists · Downloaded (per account) · Folders (Jellyfin); a row opens that account’s list', ...P, el: <HjLibraryAcc sit={HM_SIT.c} mini="queue" /> },
        { id: 'hj-lib-b', label: '(e) Library · Jellyfin only · the same section style, one account: Favourites · … · Downloaded · Folders', ...P, el: <HjLibraryAcc sit={HM_SIT.b} mini="queue" /> },
        { id: 'hj-lib-a', label: '(e) Library · Yandex only · the same section style, one account: Liked · … · Downloaded; no Folders', ...P, el: <HjLibraryAcc sit={HM_SIT.a} mini="wave" /> },
        { id: 'hj-lib-land', label: '(e) Library · phone landscape (960×540 dp at 2×) · the accounts side by side, Play / Shuffle in the title row, tabs + mini player in one bottom row', ...L, el: <HjLibraryLand /> },
        { id: 'hj-lib-c', label: '(e) Merged rows with Y / J badges (the earlier proposal)', ...P, el: <HjLibrary sit={HM_SIT.c} mini="queue" /> },
        { id: 'hj-lib-albums-c', label: '(e) A merged row opened (Albums): a pill picks the service', ...P, el: <HjAlbumsC /> },
      ] },
    ],
  };
}

Object.assign(window, { homeJfBoards, HjHomeA, HjHomeC, HjHomeCScroll, HjHomeCFull, HjHomeB, HjLibrary, HjLibraryAcc, HjLibraryLand, HjLand, HjAlbumsC });
