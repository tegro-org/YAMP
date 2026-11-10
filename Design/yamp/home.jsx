// home.jsx — 11 · Home, Library and car home from several providers (C3, C7, D19, D23, D30, D33).
// Exposes homeSections(): the canvas sections of this file, each
// { id, title, subtitle, boards: [{ id, label, w, h, el }] } — wired into YAMP.html's buildSections().
//
// One rule for every set of signed-in providers: a screen is a hero plus sections, and each part is shown
// only when a signed-in provider has the capability. Situations drawn: (a) Yandex only — identical to
// today, (b) Jellyfin only, (c) both. The built-in Radio source is a switch of its own.
// Favourites are per provider and never merged (D30). Downloaded music has no Library row and no car tile (D33).

const HM_SIT = {
  a: { y: true, j: false, radio: true }, aOff: { y: true, j: false, radio: false },
  b: { y: false, j: true, radio: true }, bOff: { y: false, j: true, radio: false },
  c: { y: true, j: true, radio: true }, cOff: { y: true, j: true, radio: false },
};

// ── Tabs and car tiles derived from the situation ────────────────────────────
// The first tab is named after its hero: Wave while My Wave exists, Home otherwise.
function HmUseTabs(sit) {
  const t = useT();
  return [
    sit.y ? { id: 'home', icon: 'waves', label: t('Wave', 'Волна') } : { id: 'home', icon: 'home', label: t('Home', 'Главная') },
    { id: 'search', icon: 'search', label: t('Search', 'Поиск') },
    { id: 'library', icon: 'library_music', label: t('Library', 'Музыка') },
    sit.radio && { id: 'radio', icon: 'radio', label: t('Radio', 'Радио') },
    { id: 'car', icon: 'directions_car', label: t('Car', 'В авто') },
  ].filter(Boolean);
}
// Car tiles (D33): the shared priority list from queues.jsx — Folders, My Wave, Liked, Mixes, Radio — minus
// the tiles that no signed-in source supports. The order never changes.
function HmUseCarTiles(sit) {
  const any = sit.y || sit.j;
  const has = { folders: sit.j, wave: sit.y, liked: any, mixes: any, radio: sit.radio };
  return useCarTiles().filter((x) => has[x.id]);
}

function HmTabItem({ tab, active, width }) {
  return (
    <div style={{ width, height: 54, borderRadius: 27, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 2,
      background: active ? 'rgba(255,255,255,0.14)' : 'transparent' }}>
      <Icon name={tab.icon} size={22} color={active ? HF.accent : '#fff'} />
      <div style={{ fontSize: 11, fontWeight: 600, color: active ? '#fff' : HF.text2 }}>{tab.label}</div>
    </div>
  );
}
function HmTabBar({ sit, active = 'home', style }) {
  const tabs = HmUseTabs(sit);
  return (
    <div style={{ height: 66, borderRadius: HFShape.bar + 1, background: HF.barFill, border: `1px solid ${HF.glassBorder}`,
      display: 'flex', alignItems: 'center', justifyContent: 'space-around', padding: '0 6px', ...style }}>
      {tabs.map((tb) => <HmTabItem key={tb.id} tab={tb} active={tb.id === active} width={70} />)}
    </div>
  );
}
// What the mini player shows: the wave track (Yandex) or the active queue's track.
function HmUseMini(kind) {
  const t = useT();
  if (!kind) return null;
  return kind === 'wave' ? [t('First song', 'Первая песня'), t('The Band', 'Группа'), 'blue'] : ['Wish You Were Here', 'Pink Floyd', 'amber'];
}
function HmPhoneChrome({ sit, active, mini }) {
  mini = HmUseMini(mini);
  return (
    <div style={{ position: 'absolute', left: 16, right: 16, bottom: 16 }}>
      {mini && <MiniPlayer title={mini[0]} artist={mini[1]} hue={mini[2]} style={{ marginBottom: 8 }} />}
      <HmTabBar sit={sit} active={active} />
    </div>
  );
}
function HmDockTabs({ sit, active = 'home' }) {
  const tabs = HmUseTabs(sit);
  return (
    <div style={{ height: 62, borderRadius: 31, background: HF.barFill, border: `1px solid ${HF.glassBorder}`, display: 'inline-flex', alignItems: 'center', padding: '0 4px', flex: '0 0 auto' }}>
      {tabs.map((tb) => <HmTabItem key={tb.id} tab={tb} active={tb.id === active} width={92} />)}
    </div>
  );
}
function HmDock({ sit, active, mini }) {
  mini = HmUseMini(mini);
  return (
    <div style={{ position: 'absolute', left: 24, right: 24, bottom: 14, display: 'flex', gap: 12, alignItems: 'center' }}>
      <HmDockTabs sit={sit} active={active} />
      {mini && <MiniPlayer title={mini[0]} artist={mini[1]} hue={mini[2]} style={{ flex: 1 }} />}
    </div>
  );
}

// ── Shared pieces ────────────────────────────────────────────────────────────
// Section header. The provider badge appears only when the screen mixes providers (DESIGN_GUIDE §8).
function HmCaps({ children, provider, trailing, big, style }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', height: big ? 44 : 40, ...style }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
        <Txt t="sectionCaps" color={HF.text2} ellipsis style={big ? { fontSize: 15 } : undefined}>{children}</Txt>
        {provider && <ProviderBadge provider={provider} size={big ? 18 : 15} />}
      </div>
      {trailing}
    </div>
  );
}
function HmMore({ children, big }) {
  return <TextAction style={big ? { fontSize: 18, height: 56, margin: '-6px 0' } : { height: 40 }}>{children}<Icon name="chevron_right" size={big ? 24 : 20} /></TextAction>;
}
// Cover tile: phone 120 dp, head unit 150 dp (`big`). `play` = start it in one tap.
function HmTile({ size = 120, hue, title, sub, glyph, mark, play, provider, big }) {
  return (
    <div style={{ width: size, flex: '0 0 auto' }}>
      <div style={{ position: 'relative' }}>
        <Cover size={size} hue={hue} radius={big ? 22 : 18} glyph={glyph} />
        {mark && (
          <div style={{ position: 'absolute', left: 6, top: 6, width: 26, height: 26, borderRadius: 13, background: HF.barFill, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Icon name={mark} size={15} />
          </div>
        )}
        {play && <GlassCircle icon="play_arrow" size={44} style={{ position: 'absolute', right: 6, bottom: 6, background: HF.barFill }} />}
      </div>
      <div className="hf-ellipsis" style={{ ...(big ? { fontSize: 17, lineHeight: '22px', fontWeight: 700 } : HFType.cardTitle), marginTop: 8 }}>{title}</div>
      {sub && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
          {provider && <ProviderBadge provider={provider} size={14} />}
          <div className="hf-ellipsis" style={{ fontSize: big ? 14 : 13, lineHeight: big ? '18px' : '17px', color: HF.text2 }}>{sub}</div>
        </div>
      )}
    </div>
  );
}

const HM_PINNED = [['Pink Floyd', 'amber', 'keep'], ['Мельница & Хелависа - альбомы', 'green', 'keep'], ['RC - Sorted', 'teal', 'keep'],
  ['Аквариум и БГ (mp3)', 'green', 'history'], ['Детские', 'rose', 'history']];
const HM_ALBUMS = [['The Wall', 'Pink Floyd', 'gray'], ['Дикие травы', 'Мельница', 'green'], ['Abbey Road', 'The Beatles', 'blue'],
  ['A Night at the Opera', 'Queen', 'rose'], ['Гранатовый альбом', 'Сплин', 'violet'], ['Master of Puppets', 'Metallica', 'amber']];
const HM_MORE_ALBUMS = [['Animals', 'Pink Floyd', 'teal', 'jellyfin'], ['Innuendo', 'Queen', 'violet', 'jellyfin'], ['Help!', 'The Beatles', 'amber', 'jellyfin']];
function HmUseSeeds() {
  const t = useT();
  return ['Pink Floyd', 'Мельница', t('Rock', 'Рок'), 'Queen', 'Сплин'];
}

// Provider sections of Home on the phone: pinned / recent folders, recently added, radio (Instant Mix).
function HmHomeSections({ badge }) {
  const t = useT(); const w = useFolderWords(); const seeds = HmUseSeeds();
  const p = badge ? 'jellyfin' : undefined;
  return (
    <React.Fragment>
      <HmCaps provider={p} style={{ margin: '0 12px 0 20px' }} trailing={<HmMore>{t('All', 'Все')}</HmMore>}>{w.folders}</HmCaps>
      <div style={{ display: 'flex', gap: 12, paddingLeft: 20, overflow: 'hidden' }}>
        {HM_PINNED.map(([name, hue, mark]) => <HmTile key={name} size={112} hue={hue} title={name} glyph="folder" mark={mark} play />)}
      </div>
      <HmCaps provider={p} style={{ margin: '8px 20px 0' }}>{t('Recently added', 'Недавно добавленные')}</HmCaps>
      <div style={{ display: 'flex', gap: 12, paddingLeft: 20, overflow: 'hidden' }}>
        {HM_ALBUMS.map(([title, artist, hue]) => <HmTile key={title} size={112} hue={hue} title={title} sub={artist} />)}
      </div>
      <HmCaps provider={p} style={{ margin: '8px 20px 0' }}>{t('Start a radio', 'Включить радио')}</HmCaps>
      <div style={{ display: 'flex', gap: 8, paddingLeft: 20, overflow: 'hidden' }}>
        {seeds.map((s) => <GlassPill key={s} icon="sensors" height={40} style={{ flex: '0 0 auto' }}>{s}</GlassPill>)}
      </div>
    </React.Fragment>
  );
}

// ── Home — phone ─────────────────────────────────────────────────────────────
// (b) Jellyfin only: no My Wave, so the hero is "Continue" — the active queue.
function HmContinueCard({ q }) {
  const t = useT();
  return (
    <div style={{ borderRadius: 28, background: HF.barFill, border: `1px solid ${HF.glassBorder}`, padding: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
        <Cover size={72} hue={q.hue} radius={16} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <Txt t="sectionCaps" color={HF.text2}>{t('Continue', 'Продолжить')}</Txt>
          <div className="hf-ellipsis" style={{ fontSize: 24, lineHeight: '30px', fontWeight: 800 }}>{q.name}</div>
        </div>
        <Knob icon="play_arrow" size={64} />
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 12 }}>
        <Txt t="caption" color={HF.text2} ellipsis style={{ flex: 1 }}>{q.track} · {q.artist}</Txt>
        <Txt t="caption" color={HF.text3}>{q.pos} / {q.total}</Txt>
      </div>
      <ProgressBar value={q.progress} style={{ marginTop: 10 }} />
    </div>
  );
}
function HmQueueChip({ q }) {
  return (
    <div style={{ width: 178, height: 60, flex: '0 0 auto', borderRadius: 18, padding: '0 12px 0 8px', display: 'flex', alignItems: 'center', gap: 10,
      background: HF.glassFill, border: `1px solid ${HF.glassBorder}` }}>
      <Cover size={44} hue={q.hue} radius={12} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <Txt t="cardTitle" ellipsis>{q.name}</Txt>
        <Txt t="caption" color={HF.text2} ellipsis>{q.track}</Txt>
      </div>
    </div>
  );
}
function HmPhoneHomeB() {
  const t = useT(); const queues = useQueues().filter((q) => q.kind !== 'wave');
  return (
    <Frame kind="phone" tint="#8A5A22">
      <div style={{ position: 'absolute', left: 20, right: 20, top: 22, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <LargeTitle>{t('Home', 'Главная')}</LargeTitle>
        <GlassCircle icon="stacks" badge={queues.length} />
      </div>
      <div style={{ position: 'absolute', left: 16, right: 16, top: 78 }}><HmContinueCard q={queues[0]} /></div>
      <div style={{ position: 'absolute', left: 16, right: 0, top: 240, display: 'flex', gap: 8, overflow: 'hidden' }}>
        {queues.slice(1).map((q) => <HmQueueChip key={q.id} q={q} />)}
      </div>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 308 }}><HmHomeSections /></div>
      <HmPhoneChrome sit={HM_SIT.b} active="home" />
    </Frame>
  );
}

// (c) Both: My Wave stays the hero — the first screen is today's. Provider sections continue below.
function HmPhoneHomeC() {
  const t = useT(); const w = useFolderWords();
  return (
    <Frame kind="phone">
      <PhoneHome />
      <HmCaps provider="jellyfin" style={{ position: 'absolute', left: 20, right: 12, top: 790 }} trailing={<HmMore>{t('All', 'Все')}</HmMore>}>{w.folders}</HmCaps>
    </Frame>
  );
}
function HmPhoneHomeCScrolled() {
  const t = useT(); const queues = useQueues();
  const next = [['A very long song title', 'Artist with a long name', 'rose'], [t('Third song', 'Третья песня'), t('Artist', 'Исполнитель'), 'green']];
  return (
    <Frame kind="phone">
      {/* content scrolled up under the collapsed title */}
      <div style={{ position: 'absolute', left: 0, right: 0, top: 70, bottom: 0, overflow: 'hidden',
        WebkitMaskImage: 'linear-gradient(180deg, transparent 0, #000 36px)', maskImage: 'linear-gradient(180deg, transparent 0, #000 36px)' }}>
        <div style={{ position: 'absolute', left: 20, top: -52, display: 'flex', gap: 12 }}>
          {next.map(([a, b, hue]) => (
            <div key={a} style={{ width: 132 }}>
              <Cover size={132} hue={hue} radius={18} />
              <Txt t="cardTitle" ellipsis style={{ marginTop: 8 }}>{a}</Txt><Txt t="caption" color={HF.text2} ellipsis>{b}</Txt>
            </div>
          ))}
        </div>
        <div style={{ position: 'absolute', left: 0, right: 0, top: 140 }}><HmHomeSections badge /></div>
      </div>
      <div style={{ position: 'absolute', left: 20, right: 20, top: 18, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ fontSize: 24, lineHeight: '30px', fontWeight: 700 }}>{t('My Wave', 'Моя волна')}</div>
        <GlassCircle icon="stacks" badge={queues.length} />
      </div>
      <HmPhoneChrome sit={HM_SIT.c} active="home" mini="wave" />
    </Frame>
  );
}

// ── Home — head unit 1920×720 (landscape with the dock) ──────────────────────
// The wide screen shows the hero and the provider sections side by side — nothing to scroll.
function HmHeadSections({ badge }) {
  const t = useT(); const w = useFolderWords(); const seeds = HmUseSeeds();
  const p = badge ? 'jellyfin' : undefined;
  return (
    <div style={{ position: 'absolute', left: 1020, top: 22, width: 852 }}>
      <HmCaps big provider={p} trailing={<HmMore big>{w.all}</HmMore>}>{w.folders}</HmCaps>
      <div style={{ display: 'flex', gap: 22, marginTop: 4 }}>
        {HM_PINNED.slice(0, 4).map(([name, hue, mark]) => <FolderTile key={name} name={name} hue={hue} mark={mark} w={196} coverH={150} font={18} />)}
      </div>
      <HmCaps big provider={p} style={{ marginTop: 20 }}>{t('Recently added', 'Недавно добавленные')}</HmCaps>
      <div style={{ display: 'flex', gap: 25, marginTop: 2 }}>
        {HM_ALBUMS.slice(0, 5).map(([title, artist, hue]) => <HmTile key={title} big size={150} hue={hue} title={title} sub={artist} />)}
      </div>
      <HmCaps big provider={p} style={{ marginTop: 14 }}>{t('Start a radio', 'Включить радио')}</HmCaps>
      <div style={{ display: 'flex', gap: 12, marginTop: 2 }}>
        {seeds.slice(0, 4).map((s) => <CarPill key={s} icon="sensors">{s}</CarPill>)}
      </div>
    </div>
  );
}
function HmHeadDivider() {
  return <div style={{ position: 'absolute', left: 988, top: 34, height: 588, width: 1, background: HF.hairline }} />;
}

function HmHeadHomeC() {
  const t = useT(); const queues = useQueues();
  const next = [['A very long song title', 'Artist with a long name', 'rose'], [t('Third song', 'Третья песня'), t('Artist', 'Исполнитель'), 'green'], [t('Fourth song', 'Четвёртая песня'), t('The Band', 'Группа'), 'violet']];
  return (
    <Frame kind="head" glowY="40%">
      {/* My Wave hero, as built: ring on the left, title and track beside it */}
      <div style={{ position: 'absolute', left: 60, top: 132 }}><SpectrumRing size={390} knob={240} /></div>
      <div style={{ position: 'absolute', left: 500, top: 24, width: 452 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <LargeTitle>{t('My Wave', 'Моя волна')}</LargeTitle>
          <GlassCircle icon="stacks" size={56} badge={queues.length} />
        </div>
        <GlassPill icon="tune" height={56} style={{ marginTop: 10, display: 'inline-flex' }}>{t('Tune the wave', 'Настроить волну')}</GlassPill>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 150 }}>
          <GlassCircle icon="heart_broken" size={60} iconSize={28} />
          <div style={{ flex: 1, textAlign: 'center', minWidth: 0 }}>
            <div className="hf-ellipsis" style={{ fontSize: 28, lineHeight: '34px', fontWeight: 800 }}>{t('First song', 'Первая песня')}</div>
            <div style={{ fontSize: 18, color: HF.text2 }}>{t('The Band', 'Группа')}</div>
          </div>
          <GlassCircle icon="favorite" size={60} iconSize={28} color={HF.love} />
        </div>
        <HmCaps big style={{ marginTop: 22 }} trailing={<HmMore big>{t('Player', 'Плеер')}</HmMore>}>{t('Next in the wave', 'Дальше в волне')}</HmCaps>
        <div style={{ display: 'flex', gap: 16, marginTop: 4 }}>
          {next.map(([a, b, hue]) => <HmTile key={a} size={120} hue={hue} title={a} sub={b} />)}
        </div>
      </div>
      <HmHeadDivider />
      <HmHeadSections badge />
      <HmDock sit={HM_SIT.c} active="home" />
    </Frame>
  );
}

function HmHeadQueueCard({ q }) {
  return (
    <div style={{ flex: 1, minWidth: 0, height: 104, borderRadius: 24, padding: '0 18px 0 14px', display: 'flex', alignItems: 'center', gap: 16,
      background: HF.glassFill, border: `1px solid ${HF.glassBorder}` }}>
      <Cover size={72} hue={q.hue} radius={16} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="hf-ellipsis" style={{ fontSize: 22, lineHeight: '28px', fontWeight: 700 }}>{q.name}</div>
        <div className="hf-ellipsis" style={{ fontSize: 16, lineHeight: '21px', color: HF.text2 }}>{q.track}</div>
        <ProgressBar value={q.progress} height={4} color="rgba(255,255,255,0.55)" style={{ marginTop: 9 }} />
      </div>
    </div>
  );
}
function HmHeadHomeB() {
  const t = useT(); const queues = useQueues().filter((q) => q.kind !== 'wave'); const q = queues[0];
  return (
    <Frame kind="head" tint="#8A5A22" glowY="40%">
      <div style={{ position: 'absolute', left: 48, top: 24, width: 904, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <LargeTitle>{t('Home', 'Главная')}</LargeTitle>
        <GlassCircle icon="stacks" size={56} badge={queues.length} />
      </div>
      {/* hero: continue the active queue */}
      <div style={{ position: 'absolute', left: 48, top: 96, width: 904, height: 316, borderRadius: 32, background: HF.barFill, border: `1px solid ${HF.glassBorder}`,
        padding: 28, display: 'flex', alignItems: 'center', gap: 32 }}>
        <Cover size={260} hue={q.hue} radius={30} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ ...HFType.sectionCaps, fontSize: 15, color: HF.text2 }}>{t('Continue', 'Продолжить')} · {q.pos} / {q.total}</div>
          <div className="hf-ellipsis" style={{ fontSize: 46, lineHeight: '56px', fontWeight: 800 }}>{q.name}</div>
          <div className="hf-ellipsis" style={{ fontSize: 26, lineHeight: '34px', fontWeight: 600, marginTop: 6 }}>{q.track}</div>
          <div className="hf-ellipsis" style={{ fontSize: 20, lineHeight: '26px', color: HF.text2 }}>{q.artist}</div>
          <ProgressBar value={q.progress} height={5} style={{ marginTop: 24 }} />
        </div>
        <Knob icon="play_arrow" size={128} />
      </div>
      <HmCaps big style={{ position: 'absolute', left: 48, top: 428, width: 904 }}>{t('Other queues', 'Другие очереди')}</HmCaps>
      <div style={{ position: 'absolute', left: 48, top: 476, width: 904, display: 'flex', gap: 16 }}>
        {queues.slice(1).map((x) => <HmHeadQueueCard key={x.id} q={x} />)}
      </div>
      <HmHeadDivider />
      <HmHeadSections />
      <HmDock sit={HM_SIT.b} active="home" />
    </Frame>
  );
}

// ── Library ──────────────────────────────────────────────────────────────────
// Rows are kinds, not providers: a row exists while any signed-in provider has that capability.
// Badges name the providers behind a row only when the Library mixes providers — (c).
function HmNavRow({ icon, title, count, last, providers }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 14, height: 60 }}>
      <IconPlaque icon={icon} />
      <div style={{ flex: 1, minWidth: 0, alignSelf: 'stretch', display: 'flex', alignItems: 'center', gap: 10, borderBottom: last ? 'none' : `1px solid ${HF.hairline}` }}>
        <div style={{ flex: 1, minWidth: 0, display: 'flex', alignItems: 'center', gap: 8 }}>
          <Txt t="rowTitle" ellipsis>{title}</Txt>
          {providers && <div style={{ display: 'flex', gap: 4, flex: '0 0 auto' }}>{providers.map((p) => <ProviderBadge key={p} provider={p} size={16} />)}</div>}
        </div>
        {count != null && <Txt t="body" color={HF.text3}>{count}</Txt>}
        <Icon name="chevron_right" size={22} color={HF.text3} />
      </div>
    </div>
  );
}
function HmUseLibrary(sit) {
  const t = useT();
  const mixed = sit.y && sit.j;
  const by = (...ps) => (mixed ? ps : undefined);
  const n = (y, j) => (sit.y ? y : 0) + (sit.j ? j : 0);
  // No Downloaded row (Settings → Storage → "Available offline") and no Radio row (FM radio is a tab) — D33.
  // Liked is one row, but two lists (D30): the counts are shown side by side, never summed.
  // Mixes belong to the app, not to a provider, so the row carries no badge.
  const own = [
    { id: 'liked', icon: 'favorite', title: t('Liked', 'Мне нравится'), count: mixed ? '312 · 87' : n(312, 87), providers: by('yandex', 'jellyfin') },
    { id: 'playlists', icon: 'playlist_play', title: t('Playlists', 'Плейлисты'), count: n(6, 9), providers: by('yandex', 'jellyfin') },
    { id: 'mixes', icon: 'auto_awesome', title: t('Mixes', 'Миксы'), count: n(2, 3) },
  ];
  const browse = !sit.j ? [] : [
    { id: 'folders', icon: 'folder', title: t('Folders', 'Папки'), count: 34, providers: by('jellyfin') },
    { id: 'artists', icon: 'artist', title: t('Artists', 'Исполнители'), count: 212, providers: by('jellyfin') },
    { id: 'albums', icon: 'album', title: t('Albums', 'Альбомы'), count: 486, providers: by('jellyfin') },
    { id: 'genres', icon: 'genres', title: t('Genres', 'Жанры'), count: 31, providers: by('jellyfin') },
  ];
  // Recently added: liked tracks on Yandex (as today), new albums on Jellyfin; one feed, newest first.
  const yTracks = [[t('First song', 'Первая песня'), t('The Band', 'Группа'), 'blue', 'yandex'], ['A very long song title', 'Artist with a long name', 'rose', 'yandex'],
    [t('Third song', 'Третья песня'), t('Artist', 'Исполнитель'), 'teal', 'yandex']];
  const jAlbums = HM_ALBUMS.map(([a, b, hue]) => [a, b, hue, 'jellyfin']);
  const recent = !sit.j ? yTracks : !sit.y ? jAlbums : [jAlbums[0], yTracks[0], jAlbums[1], yTracks[1], jAlbums[2], jAlbums[3], yTracks[2], jAlbums[4], jAlbums[5]];
  return { own, browse, recent, mixed };
}
// Phone content area above the mini player; what continues below fades out.
const HM_FADE = 'linear-gradient(180deg, #000 calc(100% - 36px), transparent 100%)';
const HM_PHONE_SCROLL = { position: 'absolute', left: 0, right: 0, top: 0, bottom: 150, padding: '22px 20px 0', overflow: 'hidden', WebkitMaskImage: HM_FADE, maskImage: HM_FADE };
// `first` = the first source in Settings → Sources, named only when several providers are signed in:
// the Library's own Play / Shuffle play that provider's Liked (D30).
function HmLibraryTop({ pillH = 52, first }) {
  const t = useT(); const big = pillH > 52;
  // One caption line. The phone has room for it under the buttons; the head unit has no spare row, so it sits beside the title.
  const note = first && (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0, ...(big ? { flex: 1, margin: '6px 24px 0' } : { marginTop: 8 }) }}>
      <ProviderBadge provider={first} size={15} />
      <Txt t="caption" color={HF.text2} ellipsis>
        {big ? t('Play and Shuffle: Liked from the first source in Settings → Sources', '«Слушать» и «Перемешать»: «Мне нравится» первого источника из «Настройки → Источники»')
          : t('Plays Liked from the first source in Settings → Sources', 'Играет «Мне нравится» первого из «Источников»')}
      </Txt>
    </div>
  );
  return (
    <React.Fragment>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <LargeTitle style={{ flex: '0 0 auto' }}>{t('My music', 'Моя музыка')}</LargeTitle>
        {big && note}
        <GlassCircle icon="settings" size={big ? 56 : 44} />
      </div>
      <div style={{ display: 'flex', gap: 12, marginTop: 14 }}>
        <MetalPill icon="play_arrow" height={pillH} style={{ flex: 1 }}>{t('Play', 'Слушать')}</MetalPill>
        <GlassPill icon="shuffle" height={pillH} style={{ flex: 1 }}>{t('Shuffle', 'Перемешать')}</GlassPill>
      </div>
      {!big && note}
    </React.Fragment>
  );
}
function HmPhoneLibrary({ sit, mini }) {
  const t = useT(); const lib = HmUseLibrary(sit);
  const rows = [...lib.own, ...lib.browse];
  return (
    <Frame kind="phone">
      <div style={HM_PHONE_SCROLL}>
        <HmLibraryTop first={lib.mixed ? 'yandex' : undefined} />
        <div style={{ marginTop: lib.mixed ? 8 : 14 }}>
          {rows.map((r) => <HmNavRow key={r.id} {...r} />)}
        </div>
        <HmCaps style={{ marginTop: 14 }}>{t('Recently added', 'Недавно добавленные')}</HmCaps>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px 12px', marginTop: 2 }}>
          {lib.recent.slice(0, 4).map(([a, b, hue, p]) => <HmTile key={a} size={180} hue={hue} title={a} sub={b} provider={lib.mixed ? p : undefined} />)}
        </div>
      </div>
      <HmPhoneChrome sit={sit} active="library" mini={mini} />
    </Frame>
  );
}
// "Liked" opened while both providers are signed in (D30): favourites are not merged. A pill picks the
// provider (in the order of Settings → Sources); the list and Play / Shuffle belong to that provider only.
function HmProviderPill({ provider, name, count, active }) {
  return (
    <FilterPill active={active} style={{ gap: 7 }}>
      <ProviderBadge provider={provider} size={16} />{name}<span style={{ opacity: 0.6, fontWeight: 600 }}>{count}</span>
    </FilterPill>
  );
}
const HM_LIKED_J = [['Wish You Were Here', 'Pink Floyd', 'amber'], ['Дороги', 'Мельница', 'green'], ['Bohemian Rhapsody', 'Queen', 'rose'],
  ['Выхода нет', 'Сплин', 'violet'], ['Come Together', 'The Beatles', 'blue'], ['Master of Puppets', 'Metallica', 'amber'],
  ['Echoes', 'Pink Floyd', 'teal'], ['Ночная кобыла', 'Мельница', 'green'], ['Hey You', 'Pink Floyd', 'gray']];
function HmPhoneLiked() {
  const t = useT();
  return (
    <Frame kind="phone">
      <div style={HM_PHONE_SCROLL}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <GlassCircle icon="arrow_back" /><GlassCircle icon="more_vert" />
        </div>
        <LargeTitle style={{ marginTop: 12 }}>{t('Liked', 'Мне нравится')}</LargeTitle>
        <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
          <HmProviderPill provider="yandex" name={t('Yandex Music', 'Яндекс Музыка')} count={312} />
          <HmProviderPill provider="jellyfin" name="Jellyfin" count={87} active />
        </div>
        <div style={{ display: 'flex', gap: 12, marginTop: 14 }}>
          <MetalPill icon="play_arrow" style={{ flex: 1 }}>{t('Play', 'Слушать')}</MetalPill>
          <GlassPill icon="shuffle" style={{ flex: 1 }}>{t('Shuffle', 'Перемешать')}</GlassPill>
        </div>
        <div style={{ marginTop: 8 }}>
          {HM_LIKED_J.map(([title, artist, hue]) => (
            <TrackRow key={title} title={title} artist={artist} hue={hue} trailing={<GlassCircle icon="favorite" size={36} color={HF.love} />} />
          ))}
        </div>
      </div>
      <HmPhoneChrome sit={HM_SIT.c} active="library" mini="queue" />
    </Frame>
  );
}

// Head unit: Liked, Playlists and Mixes keep the first column; the browse kinds take the second.
function HmHeadLibrary() {
  const t = useT(); const lib = HmUseLibrary(HM_SIT.c);
  return (
    <Frame kind="head" glowY="20%">
      <div style={{ position: 'absolute', left: 48, right: 48, top: 22 }}>
        <HmLibraryTop pillH={56} first="yandex" />
        <div style={{ display: 'flex', gap: 56, marginTop: 12 }}>
          <div style={{ flex: 1, minWidth: 0 }}>{lib.own.map((r) => <HmNavRow key={r.id} {...r} />)}</div>
          <div style={{ flex: 1, minWidth: 0 }}>{lib.browse.map((r) => <HmNavRow key={r.id} {...r} />)}</div>
        </div>
        <HmCaps big style={{ marginTop: 4 }}>{t('Recently added', 'Недавно добавленные')}</HmCaps>
        <div style={{ display: 'flex', gap: 22, overflow: 'hidden' }}>
          {[...lib.recent, ...HM_MORE_ALBUMS].map(([a, b, hue, p]) => <HmTile key={a} size={130} hue={hue} title={a} sub={b} provider={p} />)}
        </div>
      </div>
      <HmDock sit={HM_SIT.c} active="library" mini="queue" />
    </Frame>
  );
}

// ── Tabs and car tiles — one board, every situation ──────────────────────────
function HmNavCompareRow({ sit, title, note, last }) {
  const tiles = HmUseCarTiles(sit);
  const ps = [sit.y && 'yandex', sit.j && 'jellyfin', sit.radio && 'radio'].filter(Boolean);
  return (
    <div style={{ display: 'flex', alignItems: 'center', height: 124, borderBottom: last ? 'none' : `1px solid ${HF.hairline}` }}>
      <div style={{ width: 270, flex: '0 0 auto', paddingRight: 20 }}>
        <div style={{ display: 'flex', gap: 5, marginBottom: 6 }}>{ps.map((p) => <ProviderBadge key={p} provider={p} size={20} />)}</div>
        <Txt t="rowTitle">{title}</Txt>
        {note && <Txt t="caption" color={HF.text2}>{note}</Txt>}
      </div>
      <div style={{ width: 380, flex: '0 0 auto' }}><HmTabBar sit={sit} active="home" /></div>
      <div style={{ width: 468, flex: '0 0 auto', marginLeft: 40 }}><HmDockTabs sit={sit} active="home" /></div>
      {/* same tile and gap as the shared CarTileRow, which cannot leave tiles out */}
      <div style={{ display: 'flex', gap: 14, marginLeft: 40 }}>
        {tiles.map((x, i) => <CarTile key={x.id} icon={x.icon} label={x.label} active={i === 0} />)}
      </div>
    </div>
  );
}
// Where the slim 640 pane ends, measured from the start of the tile row (the row starts 28 dp from the pane's edge).
const HM_SLIM_EDGE = 640 - 28;
function HmNavCompare() {
  const t = useT();
  const col = (width, ml, text) => <Txt t="sectionCaps" color={HF.text2} style={{ width, marginLeft: ml, flex: '0 0 auto' }}>{text}</Txt>;
  const rule = (icon, text) => (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, flex: 1, minWidth: 0 }}>
      <Icon name={icon} size={22} color={HF.text2} /><Txt t="caption" color={HF.text2}>{text}</Txt>
    </div>
  );
  return (
    <div className="hf" style={{ position: 'relative', width: 2000, height: 990, overflow: 'hidden', background: HF.ground }}>
      <Backdrop tint="#22335F" glowY="0%">
        <div style={{ position: 'absolute', inset: 0, padding: '32px 40px' }}>
          <LargeTitle>{t('Tabs and car tiles follow the signed-in providers', 'Вкладки и плитки зависят от подключённых источников')}</LargeTitle>
          <div style={{ display: 'flex', alignItems: 'flex-end', marginTop: 22, paddingBottom: 6, borderBottom: `1px solid ${HF.hairline}` }}>
            {col(270, 0, t('Signed in', 'Подключено'))}
            {col(380, 0, t('Phone · tab bar', 'Телефон · панель вкладок'))}
            {col(468, 40, t('Head unit · dock', 'Магнитола · док'))}
            <div style={{ width: HM_SLIM_EDGE, marginLeft: 40, flex: '0 0 auto', display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', paddingRight: 8 }}>
              <Txt t="sectionCaps" color={HF.text2}>{t('Car mode · tiles', 'Режим «В авто» · плитки')}</Txt>
              <Txt t="caption" color={HF.text2}>{t('the slim 640 pane ends here', 'здесь кончается узкая панель 640')}</Txt>
            </div>
          </div>
          <div style={{ position: 'relative' }}>
            <HmNavCompareRow sit={HM_SIT.a} title={t('Yandex · Radio on', 'Яндекс · Радио вкл.')} note={t('Tabs exactly as today', 'Вкладки в точности как сейчас')} />
            <HmNavCompareRow sit={HM_SIT.aOff} title={t('Yandex · Radio off', 'Яндекс · Радио выкл.')} note={t('Radio tab and tile are gone', 'Нет вкладки и плитки «Радио»')} />
            <HmNavCompareRow sit={HM_SIT.b} title={t('Jellyfin · Radio on', 'Jellyfin · Радио вкл.')} note={t('No My Wave: the first tab is Home, the first tile is Folders', 'Нет Моей волны: первая вкладка — «Главная», первая плитка — «Папки»')} />
            <HmNavCompareRow sit={HM_SIT.bOff} title={t('Jellyfin · Radio off', 'Jellyfin · Радио выкл.')} />
            <HmNavCompareRow sit={HM_SIT.c} title={t('Both · Radio on', 'Оба · Радио вкл.')} note={t('All five tiles. The slim pane shows four; Radio scrolls in', 'Все пять плиток. В узкой панели видны четыре, «Радио» — прокруткой')} />
            <HmNavCompareRow sit={HM_SIT.cOff} title={t('Both · Radio off', 'Оба · Радио выкл.')} note={t('Four tiles: they fit in every pane', 'Четыре плитки: помещаются в любой панели')} last />
            {/* edge of the slim pane: what lies to the right of it is reached by scrolling the row */}
            <div style={{ position: 'absolute', left: 270 + 380 + 40 + 468 + 40 + HM_SLIM_EDGE, top: -24, bottom: 0, borderLeft: `2px dashed ${HF.text3}` }} />
          </div>
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 32, paddingTop: 16, borderTop: `1px solid ${HF.hairline}` }}>
            {rule('waves', t('First tab = its hero: Wave while My Wave exists (Yandex), otherwise Home. Search, Library and Car are always there.',
              'Первая вкладка названа по главному блоку: «Волна», пока есть Моя волна (Яндекс), иначе «Главная». Поиск, Музыка и «В авто» есть всегда.'))}
            {rule('radio', t('The Radio tab and tile exist only while the built-in Radio source is switched on. Dislike is shown only on Yandex tracks.',
              'Вкладка и плитка «Радио» есть, только пока включён встроенный источник «Радио». «Не нравится» — только на треках Яндекса.'))}
            {rule('grid_view', t('Car tiles are one priority list (D33): Folders, My Wave, Liked, Mixes, Radio. A tile exists only while a signed-in source supports it: Folders — Jellyfin, My Wave — Yandex, Liked and Mixes — any source. There is no Offline tile and no Charts tile.',
              'Плитки — один список по приоритету (D33): Папки, Моя волна, Любимое, Миксы, Радио. Плитка есть, только пока её поддерживает подключённый источник: Папки — Jellyfin, Моя волна — Яндекс, Любимое и Миксы — любой. Плиток «Скачанные» и «Хиты» нет.'))}
            {rule('swipe', t('As many tiles as fit are shown; the row scrolls sideways for the rest. Five fit on the full 1920 screen and in the wide 1280 pane, four in the slim 640 pane (dashed line). The Liked tile plays the first source in Settings\u00a0→\u00a0Sources (D30).',
              'Показано столько плиток, сколько помещается; остальные — прокруткой вбок. Пять помещаются на полном экране 1920 и в широкой панели 1280, четыре — в узкой 640 (пунктир). Плитка «Любимое» играет первый источник из «Настройки\u00a0→\u00a0Источники» (D30).'))}
          </div>
        </div>
      </Backdrop>
    </div>
  );
}

// ── Sections ─────────────────────────────────────────────────────────────────
function homeSections() {
  const P = FRAMES.phone, H = FRAMES.head;
  const sz = (f) => ({ w: f.w, h: f.h });
  return [
    { id: 'home-home', title: '11a · Home — a hero plus provider sections',
      subtitle: 'Home is composed from what the signed-in providers can do (C3, C7, D19): a hero, then sections. The hero is My Wave while Yandex is signed in — so Yandex only is today’s screen unchanged, and adding Jellyfin only adds sections below it (D23). Without My Wave the hero is “Continue” (the active queue). Folders, Recently added and Start a radio (Instant Mix) come from Jellyfin.',
      boards: [
        { id: 'hm-home-a', label: '(a) Yandex only · Home = My Wave, as today', ...sz(P), el: <Frame kind="phone"><PhoneHome /></Frame> },
        { id: 'hm-home-b', label: '(b) Jellyfin only · Continue + folders, new albums, radio', ...sz(P), el: <HmPhoneHomeB /> },
        { id: 'hm-home-c', label: '(c) Both · first screen is today’s My Wave, sections peek below', ...sz(P), el: <HmPhoneHomeC /> },
        { id: 'hm-home-c-scroll', label: '(c) Both · scrolled: Jellyfin sections, mini player returns', ...sz(P), el: <HmPhoneHomeCScrolled /> },
        { id: 'hm-head-home-c', label: 'Head unit · (c) Both · My Wave and the sections side by side', ...sz(H), el: <HmHeadHomeC /> },
        { id: 'hm-head-home-b', label: 'Head unit · (b) Jellyfin only · Continue and the sections', ...sz(H), el: <HmHeadHomeB /> },
      ] },
    { id: 'home-library', title: '11b · Library — rows by kind, provider badges when mixed',
      subtitle: 'One Library for all providers: a row is a kind of thing (Liked, Playlists, Mixes, Folders, Artists, Albums, Genres) and exists while a signed-in provider has that capability (C3, C7). There is no Downloaded row — downloaded music is browsed under Settings → Storage → “Available offline” — and no Radio row, because FM radio stays a tab (D33). Badges appear only when providers are mixed (DESIGN_GUIDE §8). Favourites stay per provider and are not merged (D11, D30): the one Liked row opens a list whose provider is picked by a pill, and Play / Shuffle there act on that provider; the Library’s own Play / Shuffle use the first source in Settings → Sources.',
      boards: [
        { id: 'hm-lib-a', label: '(a) Yandex only · Liked, Playlists, Mixes — no Downloaded row', ...sz(P), el: <HmPhoneLibrary sit={HM_SIT.a} mini="wave" /> },
        { id: 'hm-lib-b', label: '(b) Jellyfin only · browse kinds added, no badges', ...sz(P), el: <HmPhoneLibrary sit={HM_SIT.b} mini="queue" /> },
        { id: 'hm-lib-c', label: '(c) Both · same rows, badges name the providers; Play / Shuffle use the first source', ...sz(P), el: <HmPhoneLibrary sit={HM_SIT.c} mini="queue" /> },
        { id: 'hm-lib-liked-c', label: '(c) Both · Liked opened: a pill picks the provider, Play / Shuffle act on it', ...sz(P), el: <HmPhoneLiked /> },
        { id: 'hm-head-lib-c', label: 'Head unit · (c) Both · two columns of rows', ...sz(H), el: <HmHeadLibrary /> },
      ] },
    { id: 'home-nav', title: '11c · Tabs and car tiles by signed-in providers',
      subtitle: 'The tab bar, the landscape dock and the car-mode tile row are built from the same capabilities (C7). The tabs of Yandex with Radio are exactly today’s; the Radio tab follows the built-in Radio source. Car tiles are one priority list in a fixed order — Folders, My Wave, Liked, Mixes, Radio — and a tile exists only while a signed-in source supports it (Folders: Jellyfin; My Wave: Yandex; Liked, Mixes: any; Radio: the Radio source); there is no Offline and no Charts tile (D33). As many tiles as fit are shown and the row scrolls sideways for the rest: five fit on the full 1920 screen and in the wide 1280 pane, four in the slim 640 pane. The Liked tile plays the first source in Settings → Sources (D30).',
      boards: [
        { id: 'hm-nav', label: 'Tab bar · dock · car tiles in every situation', w: 2000, h: 990, el: <HmNavCompare /> },
      ] },
  ];
}

Object.assign(window, { homeSections });
