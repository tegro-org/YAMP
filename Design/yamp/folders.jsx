// folders.jsx — Phase 4b: folder browser (D12). The physical folder tree is a first-class way to browse.
// Tap budget in the car: pinned / recent folder plays in 1 tap from car home; any folder in ≤ 3 taps.
// Row / tile tap = open the folder; the play button on it = play the whole folder (recursive).

// The library root: flat, hand-named folders, Latin then Cyrillic (natural sort).
const ROOT_FOLDERS = [
  ['Fleur - Дискография (2000-2026)', 'violet', 14], ['Lindemann - Discography - 2015 - 2026', 'rose', 6], ['Louna', 'amber', 9],
  ['Metallica (mp3)', 'gray', 12], ['Pink Floyd', 'amber', 15], ['Queen', 'rose', 18], ['RC - Sorted', 'teal', 48], ['The Beatles', 'blue', 13],
  ['Аквариум и БГ (mp3)', 'green', 31], ['Детские', 'rose', 7], ['Мельница & Хелависа - альбомы', 'green', 8],
  ['Мельница & Хелависа - концерты', 'teal', 5], ['Сплин', 'blue', 16], ['Чайф', 'amber', 11],
];
const ALBUMS = [['2003 — Перевал', 'teal', 12], ['2005 — Зов крови', 'rose', 14], ['2006 — Зов крови (live)', 'violet', 9], ['2009 — Дикие травы', 'green', 13],
  ['2012 — Ангелофрения', 'blue', 12], ['2015 — Алхимия', 'amber', 14], ['2016 — Химера', 'violet', 11], ['2021 — Манускрипт', 'gray', 12]];
const TRACKS = [['Невеста Полоза', '5:02'], ['Дороги', '4:12'], ['Дракон', '4:31'], ['Ведьма', '3:48'], ['Княже', '4:55'], ['Шелкопряд', '4:07']];

function useFolderWords() {
  const t = useT();
  return {
    folders: t('Folders', 'Папки'), filter: t('Filter folders', 'Фильтр по папкам'), pinned: t('Pinned', 'Закреплённые'),
    all: t('All folders', 'Все папки'), play: t('Play', 'Слушать'), shuffle: t('Shuffle', 'Перемешать'), radio: t('Radio', 'Радио'),
    add: t('Add to queue', 'В очередь'), nFolders: (n) => t(`${n} folders`, `${n} папок`), nTracks: (n) => t(`${n} tracks`, `${n} треков`),
    tracks: t('Tracks', 'Треки'), recent: t('Recent', 'Недавние'),
  };
}

// ── Phone ────────────────────────────────────────────────────────────────────
function FolderRow({ name, hue, count, sub, last }) {
  const w = useFolderWords();
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, height: 64 }}>
      <Cover size={48} hue={hue} glyph="folder" />
      <div style={{ flex: 1, minWidth: 0, alignSelf: 'stretch', display: 'flex', alignItems: 'center', gap: 8, borderBottom: last ? 'none' : `1px solid ${HF.hairline}` }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <Txt t="rowTitle" ellipsis>{name}</Txt>
          <Txt t="caption" color={HF.text2} ellipsis>{sub || w.nFolders(count)}</Txt>
        </div>
        <GlassCircle icon="play_arrow" size={40} />
        <Icon name="chevron_right" size={22} color={HF.text3} />
      </div>
    </div>
  );
}
function Crumbs({ path, big }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: big ? 10 : 6, minWidth: 0, overflow: 'hidden' }}>
      {path.map((p, i) => {
        const last = i === path.length - 1;
        return (
          <React.Fragment key={i}>
            {i > 0 && <Icon name="chevron_right" size={big ? 26 : 18} color={HF.text3} />}
            <div className="hf-ellipsis" style={{ height: big ? 60 : 36, lineHeight: big ? '58px' : '34px', borderRadius: 30, padding: big ? '0 22px' : '0 12px', flex: last ? '0 1 auto' : '0 0 auto',
              maxWidth: big ? 560 : 220, fontSize: big ? 20 : 14, fontWeight: 700, color: last ? '#fff' : HF.text2,
              background: last ? 'rgba(255,255,255,0.16)' : HF.glassFill, border: `1px solid ${HF.glassBorder}` }}>{p}</div>
          </React.Fragment>
        );
      })}
    </div>
  );
}
function PhoneTop({ title, crumbs }) {
  return (
    <div style={{ position: 'absolute', left: 16, right: 16, top: 20, display: 'flex', alignItems: 'center', gap: 12 }}>
      <GlassCircle icon="arrow_back" />
      {crumbs ? <Crumbs path={crumbs} /> : <LargeTitle>{title}</LargeTitle>}
    </div>
  );
}

function PhoneFolders({ filter }) {
  const w = useFolderWords(); const t = useT();
  const list = filter ? ROOT_FOLDERS.filter((f) => f[0].toLowerCase().includes(filter)) : ROOT_FOLDERS.slice(0, 7);
  return (
    <Frame kind="phone">
      <PhoneTop title={w.folders} />
      <div style={{ position: 'absolute', left: 16, right: 16, top: 82 }}>
        <Field value={filter} placeholder={w.filter} icon="search" />
        {!filter && (
          <React.Fragment>
            <SectionCaps style={{ margin: '18px 4px 10px' }}>{w.pinned}</SectionCaps>
            <div style={{ display: 'flex', gap: 8, overflow: 'hidden' }}>
              {['Pink Floyd', 'Мельница & Хелависа - альбомы', 'RC - Sorted'].map((n) => (
                <GlassPill key={n} icon="keep" height={40} style={{ flex: '0 0 auto', maxWidth: 190 }}><span className="hf-ellipsis">{n}</span></GlassPill>
              ))}
            </div>
          </React.Fragment>
        )}
        <SectionCaps style={{ margin: '18px 4px 4px' }}>{filter ? t(`Found · ${list.length}`, `Найдено · ${list.length}`) : `${w.all} · 34`}</SectionCaps>
        <div style={{ padding: '0 4px' }}>
          {list.map(([name, hue, count], i) => <FolderRow key={name} name={name} hue={hue} count={count} last={i === list.length - 1} />)}
        </div>
      </div>
      <PhoneChrome active="library" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />
    </Frame>
  );
}

function FolderActions({ w }) {
  return (
    <React.Fragment>
      <div style={{ display: 'flex', gap: 10 }}>
        <MetalPill icon="play_arrow" style={{ flex: 1 }}>{w.play}</MetalPill>
        <GlassPill icon="shuffle" style={{ flex: 1 }}>{w.shuffle}</GlassPill>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
        <GlassPill icon="sensors" height={40} style={{ flex: 1 }}>{w.radio}</GlassPill>
        <GlassPill icon="stacks" height={40} style={{ flex: 1.3 }}>{w.add}</GlassPill>
        <GlassCircle icon="keep" size={40} /><GlassCircle icon="more_horiz" size={40} />
      </div>
    </React.Fragment>
  );
}

function PhoneFolderLevel() {
  const w = useFolderWords();
  return (
    <Frame kind="phone" tint="#1E6B4A">
      <PhoneTop crumbs={[w.folders, 'Мельница & Хелависа - альбомы']} />
      <div style={{ position: 'absolute', left: 16, right: 16, top: 84 }}>
        <div style={{ fontSize: 26, lineHeight: '31px', fontWeight: 800 }}>Мельница & Хелависа - альбомы</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, margin: '6px 0 16px' }}>
          <ProviderBadge provider="jellyfin" size={16} /><Txt t="caption" color={HF.text2}>{w.nFolders(8)} · {w.nTracks(97)}</Txt>
        </div>
        <FolderActions w={w} />
        <SectionCaps style={{ margin: '20px 4px 4px' }}>{w.folders}</SectionCaps>
        <div style={{ padding: '0 4px' }}>
          {ALBUMS.slice(0, 5).map(([name, hue, n], i) => <FolderRow key={name} name={name} hue={hue} sub={w.nTracks(n)} last={i === 4} />)}
        </div>
      </div>
      <PhoneChrome active="library" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />
    </Frame>
  );
}

function PhoneFolderAlbum() {
  const w = useFolderWords();
  return (
    <Frame kind="phone" tint="#1E6B4A">
      <PhoneTop crumbs={[w.folders, 'Мельница & Хелависа…', '2009 — Дикие травы']} />
      <div style={{ position: 'absolute', left: 16, right: 16, top: 84 }}>
        <div style={{ display: 'flex', gap: 14, alignItems: 'center', marginBottom: 16 }}>
          <Cover size={96} hue="green" radius={16} />
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 24, lineHeight: '29px', fontWeight: 800 }}>2009 — Дикие травы</div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 6 }}>
              <ProviderBadge provider="jellyfin" size={16} /><Txt t="caption" color={HF.text2}>{w.nTracks(13)} · 54:10</Txt>
            </div>
          </div>
        </div>
        <FolderActions w={w} />
        <SectionCaps style={{ margin: '20px 4px 4px' }}>{w.tracks}</SectionCaps>
        <div style={{ padding: '0 4px' }}>
          {TRACKS.slice(0, 5).map(([title, d], i) => (
            <div key={title} style={{ display: 'flex', alignItems: 'center', gap: 12, height: 56, borderBottom: i === 4 ? 'none' : `1px solid ${HF.hairline}` }}>
              <Txt t="body" color={HF.text3} style={{ width: 22, textAlign: 'right' }}>{i + 1}</Txt>
              <Txt t="rowTitle" ellipsis style={{ flex: 1 }}>{title}</Txt>
              <Txt t="caption" color={HF.text3}>{d}</Txt><Icon name="more_vert" size={22} color={HF.text2} />
            </div>
          ))}
        </div>
      </div>
      <PhoneChrome active="library" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />
    </Frame>
  );
}

function PhoneFolderMenu() {
  const t = useT();
  return (
    <Frame kind="phone" tint="#1E6B4A">
      <Scrim opacity={0.25} />
      <Sheet height={520}>
        <div style={{ padding: '18px 24px 0' }}>
          <Txt t="dialogTitle" ellipsis>Мельница & Хелависа - концерты</Txt>
          <Txt t="caption" color={HF.text2} style={{ marginBottom: 10 }}>{t('5 folders · 71 tracks', '5 папок · 71 трек')}</Txt>
          <MenuRow icon="play_arrow">{t('Play', 'Слушать')}</MenuRow>
          <MenuRow icon="shuffle">{t('Shuffle', 'Перемешать')}</MenuRow>
          <MenuRow icon="skip_next">{t('Play next', 'Играть следующим')}</MenuRow>
          <MenuRow icon="stacks">{t('Add to queue…', 'Добавить в очередь…')}</MenuRow>
          <MenuRow icon="sensors">{t('Start radio', 'Запустить радио')}</MenuRow>
          <MenuRow icon="keep">{t('Pin to car home', 'Закрепить на экране авто')}</MenuRow>
        </div>
      </Sheet>
    </Frame>
  );
}

// ── Head unit ────────────────────────────────────────────────────────────────
// Tile tap = open; the play button = play the folder. `mark` = pinned / recent glyph.
function FolderTile({ name, hue, sub, w = 220, coverH = 190, mark, font = 20 }) {
  return (
    <div style={{ width: w, flex: '0 0 auto' }}>
      <div style={{ position: 'relative' }}>
        <Cover size={w} hue={hue} radius={24} glyph="folder" style={{ height: coverH }} />
        {mark && <div style={{ position: 'absolute', left: 10, top: 10, width: 36, height: 36, borderRadius: 18, background: 'rgba(5,6,10,0.55)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}><Icon name={mark} size={20} /></div>}
        <Knob icon="play_arrow" size={64} style={{ position: 'absolute', right: 10, bottom: 10 }} />
      </div>
      <div className="hf-ellipsis" style={{ fontSize: font, lineHeight: `${font + 6}px`, fontWeight: 700, marginTop: 8 }}>{name}</div>
      {sub && <div style={{ fontSize: font - 5, color: HF.text2 }}>{sub}</div>}
    </div>
  );
}
function NowPlayingCapsule({ width = 440 }) {
  return (
    <div style={{ height: 64, width, borderRadius: 32, background: HF.barFill, border: `1px solid ${HF.glassBorder}`, display: 'flex', alignItems: 'center', gap: 12, padding: '0 8px' }}>
      <Cover size={48} hue="amber" radius={24} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="hf-ellipsis" style={{ fontSize: 18, fontWeight: 700 }}>Wish You Were Here</div>
        <div className="hf-ellipsis" style={{ fontSize: 14, color: HF.text2 }}>Pink Floyd</div>
      </div>
      <Knob icon="pause" size={48} /><Icon name="skip_next" size={32} style={{ margin: '0 8px' }} />
    </div>
  );
}
// Only the letters that exist at this level, Latin then Cyrillic.
function AzRail({ style }) {
  const cell = (ch, on) => (
    <div key={ch} style={{ width: 56, height: 56, borderRadius: 16, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 22, fontWeight: 800,
      background: on ? 'rgba(255,255,255,0.16)' : HF.glassFill, border: `1px solid ${HF.glassBorder}` }}>{ch}</div>
  );
  return (
    <div style={{ display: 'flex', gap: 8, ...style }}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>{['F', 'L', 'M', 'P', 'Q', 'R', 'T'].map((c, i) => cell(c, i === 0))}</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>{['А', 'Д', 'М', 'С', 'Ч'].map((c) => cell(c))}</div>
    </div>
  );
}

function CarFolders() {
  const w = useFolderWords(); const t = useT();
  return (
    <Frame kind="head" tint="#22335F" glowY="20%">
      <div style={{ position: 'absolute', left: 48, right: 48, top: 28, display: 'flex', alignItems: 'center', gap: 16 }}>
        <GlassCircle icon="arrow_back" size={60} />
        <div style={{ fontSize: 40, fontWeight: 800, marginRight: 8 }}>{w.folders}</div>
        <CarPill icon="search">{t('Filter', 'Фильтр')}</CarPill>
        <div style={{ flex: 1 }} />
        <NowPlayingCapsule />
      </div>
      <div style={{ position: 'absolute', left: 48, top: 122, width: 1690, display: 'flex', flexWrap: 'wrap', gap: '18px 20px' }}>
        {ROOT_FOLDERS.map(([name, hue, n]) => <FolderTile key={name} name={name} hue={hue} sub={w.nFolders(n)} w={224} coverH={186} />)}
      </div>
      <AzRail style={{ position: 'absolute', right: 40, top: 122 }} />
    </Frame>
  );
}

function CarFolderLevel() {
  const w = useFolderWords();
  return (
    <Frame kind="head" tint="#1E6B4A" glowY="20%">
      <div style={{ position: 'absolute', left: 48, right: 48, top: 28, display: 'flex', alignItems: 'center', gap: 16 }}>
        <GlassCircle icon="arrow_back" size={60} />
        <Crumbs big path={[w.folders, 'Мельница & Хелависа - альбомы']} />
        <div style={{ flex: 1 }} />
        <NowPlayingCapsule />
      </div>
      <div style={{ position: 'absolute', left: 48, top: 130, width: 470 }}>
        <div style={{ fontSize: 38, lineHeight: '44px', fontWeight: 800 }}>Мельница & Хелависа - альбомы</div>
        <div style={{ fontSize: 20, color: HF.text2, margin: '8px 0 22px' }}>{w.nFolders(8)} · {w.nTracks(97)}</div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          <MetalPill icon="play_arrow" height={76} style={{ fontSize: 22 }}>{w.play}</MetalPill>
          <GlassPill icon="shuffle" height={76} style={{ fontSize: 22 }}>{w.shuffle}</GlassPill>
          <GlassPill icon="sensors" height={76} style={{ fontSize: 22 }}>{w.radio}</GlassPill>
          <GlassPill icon="stacks" height={76} style={{ fontSize: 22 }}>{w.add}</GlassPill>
        </div>
      </div>
      <div style={{ position: 'absolute', left: 566, top: 130, width: 1310, display: 'flex', flexWrap: 'wrap', gap: '18px 20px' }}>
        {ALBUMS.map(([name, hue, n]) => <FolderTile key={name} name={name} hue={hue} sub={w.nTracks(n)} w={246} coverH={186} />)}
      </div>
    </Frame>
  );
}

// Car home: the far column gets pinned and recent folders. A tile's play button starts it — 1 tap.
function CarHomeFolders() {
  const w = useFolderWords();
  const tiles = [['Pink Floyd', 'amber', 'keep'], ['Мельница & Хелависа - альбомы', 'green', 'keep'], ['RC - Sorted', 'teal', 'keep'],
    ['Аквариум и БГ (mp3)', 'green', 'history'], ['Детские', 'rose', 'history']];
  const far = (
    <React.Fragment>
      <div style={{ position: 'absolute', left: 880, top: 96 }}><Cover size={210} hue="amber" radius={28} style={{ boxShadow: '0 0 70px rgba(224,160,80,0.4)' }} /></div>
      <CarNext rows={2} compact style={{ position: 'absolute', left: 1120, top: 104, width: 520 }} />
      <div style={{ position: 'absolute', left: 880, top: 338, ...HFType.sectionCaps, fontSize: 15, color: HF.text2 }}>{w.pinned} · {w.recent}</div>
      <div style={{ position: 'absolute', left: 880, top: 372, display: 'flex', gap: 18 }}>
        {tiles.map(([name, hue, mark]) => <FolderTile key={name} name={name} hue={hue} mark={mark} w={182} coverH={150} font={18} />)}
      </div>
    </React.Fragment>
  );
  return <Frame kind="head" tint="#8A5A22" glowY="40%"><CarHome far={far} /></Frame>;
}

Object.assign(window, { PhoneFolders, PhoneFolderLevel, PhoneFolderAlbum, PhoneFolderMenu, CarFolders, CarFolderLevel, CarHomeFolders, FolderTile, FolderRow, Crumbs, NowPlayingCapsule, ROOT_FOLDERS, useFolderWords });
