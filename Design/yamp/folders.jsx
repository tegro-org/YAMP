// folders.jsx — Phase 4b: Jellyfin folder browser (docs/multi-source-player.md §Phase 4b; tasks/todo.md "gaps after
// v0.5.0", refreshed 2026-11-19 to the new player style).
// Library → a Jellyfin account → Folders opens the folder tree directly at the library root.
//   Row / tile tap = open · the play button on it = play the whole folder (recursive, album order).
//   Hold = the folder menu, the album menu's look: Play now · Shuffle · Play next · Add to end · Add to queue… · Pin.
//   A filter field on every level (phone; in the car the A–Z rail, the field only when parked); pinned folders on
//   top of their level; breadcrumbs jump to any level.
//   Folders without art get a drawn placeholder (a folder plate with the name's monogram), never an empty square.
// In the car the folders are one tab of the car Library (carbrowse.jsx, `CbShell`), opened from the player's ⋮.

// ── Sample data ──────────────────────────────────────────────────────────────
// The library root: flat, hand-named folders, Latin then Cyrillic (natural sort). [name, hue, subfolders].
const ROOT_FOLDERS = [
  ['Fleur - Дискография (2000-2026)', 'violet', 14], ['Lindemann - Discography - 2015 - 2026', 'rose', 6], ['Louna', 'amber', 9],
  ['Metallica (mp3)', 'gray', 12], ['Pink Floyd', 'amber', 15], ['Queen', 'rose', 18], ['RC - Sorted', 'teal', 48], ['The Beatles', 'blue', 13],
  ['Аквариум и БГ (mp3)', 'green', 31], ['Детские', 'rose', 7], ['Мельница & Хелависа - альбомы', 'green', 8],
  ['Мельница & Хелависа - концерты', 'teal', 5], ['Сплин', 'blue', 16], ['Чайф', 'amber', 11],
];
// Folders that have art (a folder image, or the first album's cover); every other folder gets the placeholder.
const FD_ART = { 'Pink Floyd': 'amber', 'Queen': 'rose', 'The Beatles': 'blue', 'Сплин': 'violet', 'Louna': 'amber',
  '2005 — Зов крови': 'rose', '2009 — Дикие травы': 'green', '2012 — Ангелофрения': 'blue', '2015 — Алхимия': 'amber' };
const FD_PINNED = ['Pink Floyd', 'Мельница & Хелависа - альбомы', 'RC - Sorted'];
const FD_TRACKS = { 'Fleur - Дискография (2000-2026)': 168, 'Lindemann - Discography - 2015 - 2026': 61, 'Louna': 104, 'Metallica (mp3)': 151,
  'Pink Floyd': 182, 'Queen': 224, 'RC - Sorted': 912, 'The Beatles': 211, 'Аквариум и БГ (mp3)': 402, 'Детские': 88,
  'Мельница & Хелависа - альбомы': 97, 'Мельница & Хелависа - концерты': 71, 'Сплин': 190, 'Чайф': 143 };
const ALBUMS = [['2003 — Перевал', 'teal', 12], ['2005 — Зов крови', 'rose', 14], ['2006 — Зов крови (live)', 'violet', 9], ['2009 — Дикие травы', 'green', 13],
  ['2012 — Ангелофрения', 'blue', 12], ['2015 — Алхимия', 'amber', 14], ['2016 — Химера', 'violet', 11], ['2021 — Манускрипт', 'gray', 12]];
const TRACKS = [['Невеста Полоза', '5:02'], ['Дороги', '4:12'], ['Дракон', '4:31'], ['Ведьма', '3:48'], ['Княже', '4:55'], ['Шелкопряд', '4:07']];

// Russian plural: one / few / many.
function fdPl(n, one, few, many) {
  const a = n % 10, b = n % 100;
  return a === 1 && b !== 11 ? one : a >= 2 && a <= 4 && (b < 12 || b > 14) ? few : many;
}
function useFolderWords() {
  const t = useT();
  return {
    folders: t('Folders', 'Папки'), filter: t('Filter folders', 'Фильтр по папкам'), filterHere: t('Filter in this folder', 'Фильтр в этой папке'),
    pinned: t('Pinned', 'Закреплённые'), all: t('All folders', 'Все папки'), play: t('Play', 'Слушать'), shuffle: t('Shuffle', 'Перемешать'),
    radio: t('Radio', 'Радио'), add: t('Add to queue', 'В очередь'), addTo: t('Add to queue…', 'В очередь…'),
    nFolders: (n) => t(`${n} folder${n === 1 ? '' : 's'}`, `${n} ${fdPl(n, 'папка', 'папки', 'папок')}`),
    nTracks: (n) => t(`${n} track${n === 1 ? '' : 's'}`, `${n} ${fdPl(n, 'трек', 'трека', 'треков')}`),
    tracks: t('Tracks', 'Треки'), recent: t('Recent', 'Недавние'), folder: t('Folder', 'Папка'),
    pin: t('Pin to the top', 'Закрепить сверху'), unpin: t('Unpin', 'Открепить'),
  };
}

// ── Folder art and the placeholder ───────────────────────────────────────────
// Monogram: the part before " - ", without "(…)", initials of up to two words ("Мельница & Хелависа" → МХ,
// "RC - Sorted" → RC, "Детские" → Д).
function fdMonogram(name) {
  const head = name.split(' - ')[0].replace(/\(.*?\)/g, ' ');
  const words = head.split(/\s+/).filter((x) => /^\p{L}/u.test(x));   // years and dashes skipped ("2003 — Перевал" → П)
  if (words.length === 1) return /^[\p{Lu}\d]{2,3}$/u.test(words[0]) ? words[0].slice(0, 2) : words[0][0].toUpperCase();
  return words.slice(0, 2).map((x) => x[0].toUpperCase()).join('');
}
function fdHue(name) {
  const hues = ['violet', 'teal', 'rose', 'amber', 'blue', 'green'];
  let h = 0; for (const ch of name) h = (h * 31 + ch.codePointAt(0)) >>> 0;
  return hues[h % hues.length];
}
// The placeholder: a steel plate with a drawn folder (tab + body) carrying the monogram, faintly tinted by the name.
function FdPlaceholder({ name, size = 48, radius = 10, height }) {
  const hgt = height || size, u = Math.min(size, hgt);
  const [tint] = COVER_HUES[fdHue(name)];
  return (
    <div style={{ position: 'relative', width: size, height: hgt, borderRadius: radius, flex: '0 0 auto', overflow: 'hidden',
      background: `linear-gradient(160deg, ${HF.steelLight} 0%, ${HF.steelDark} 100%)`, border: `1px solid ${HF.glassBorder}`,
      boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.16)' }}>
      <div style={{ position: 'absolute', inset: 0, background: `radial-gradient(120% 90% at 0% 0%, ${tint}55 0%, transparent 65%)` }} />
      <div style={{ position: 'absolute', left: '14%', top: (hgt - u * 0.62) / 2 - u * 0.07, width: u * 0.3, height: u * 0.1,
        borderRadius: `${u * 0.04}px ${u * 0.04}px 0 0`, background: 'rgba(255,255,255,0.16)' }} />
      <div style={{ position: 'absolute', left: '14%', right: '14%', top: (hgt - u * 0.62) / 2 + u * 0.02, height: u * 0.6, borderRadius: u * 0.07,
        background: 'rgba(255,255,255,0.08)', border: `${u >= 96 ? 2 : 1}px solid rgba(255,255,255,0.22)`,
        display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <div style={{ fontSize: Math.max(11, Math.round(u * 0.25)), fontWeight: 800, letterSpacing: '0.02em', color: 'rgba(255,255,255,0.88)', lineHeight: 1 }}>{fdMonogram(name)}</div>
      </div>
    </div>
  );
}
// Art when the folder has it, else the placeholder.
function FdArt({ name, art, size = 48, radius = 10, height }) {
  return art ? <Cover size={size} hue={art} radius={radius} style={height ? { height } : undefined} /> : <FdPlaceholder name={name} size={size} radius={radius} height={height} />;
}

// ── Phone ────────────────────────────────────────────────────────────────────
// `art` given (hue or null) = the Phase 4b look (art or placeholder); left out = the old glyph cover (search, mix).
// `pinned` = a pin before the subtitle; `pressed` = ringed (a hold); `match` = the filter text to highlight.
function FdHit({ text, match }) {
  if (!match) return text;
  const i = text.toLowerCase().indexOf(match.toLowerCase());
  if (i < 0) return text;
  return <React.Fragment>{text.slice(0, i)}<span style={{ color: '#fff', background: 'rgba(10,108,255,0.45)', borderRadius: 4, padding: '0 1px' }}>{text.slice(i, i + match.length)}</span>{text.slice(i + match.length)}</React.Fragment>;
}
function FolderRow({ name, hue, art, count, sub, last, pinned, pressed, match }) {
  const w = useFolderWords();
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, height: 64, borderRadius: 14, margin: pressed ? '0 -6px' : 0, padding: pressed ? '0 6px' : 0,
      boxShadow: pressed ? `0 0 0 2px ${HF.accent}` : 'none', background: pressed ? HF.glassFill : 'transparent' }}>
      {art !== undefined ? <FdArt name={name} art={art} size={48} /> : <Cover size={48} hue={hue} glyph="folder" />}
      <div style={{ flex: 1, minWidth: 0, alignSelf: 'stretch', display: 'flex', alignItems: 'center', gap: 8, borderBottom: last || pressed ? 'none' : `1px solid ${HF.hairline}` }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <Txt t="rowTitle" ellipsis><FdHit text={name} match={match} /></Txt>
          <div style={{ display: 'flex', alignItems: 'center', gap: 4, minWidth: 0 }}>
            {pinned && <Icon name="keep" size={14} color={HF.text2} />}
            <Txt t="caption" color={HF.text2} ellipsis>{sub || w.nFolders(count)}</Txt>
          </div>
        </div>
        <GlassCircle icon="play_arrow" size={40} />
      </div>
    </div>
  );
}
// Breadcrumbs: every crumb is a chip that jumps to its level; the last is the current folder. `lead` = an element
// before the first crumb's text (the account mark). Long paths collapse the middle into "…".
function Crumbs({ path, big, lead }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: big ? 8 : 4, minWidth: 0, overflow: 'hidden', flex: '0 1 auto' }}>
      {path.map((p, i) => {
        const last = i === path.length - 1;
        return (
          <React.Fragment key={i}>
            {i > 0 && <Icon name="chevron_right" size={big ? 26 : 16} color={HF.text3} style={{ flexShrink: 0 }} />}
            <div style={{ height: big ? 60 : 34, borderRadius: 30, padding: big ? '0 22px' : '0 11px', flex: last ? '0 1 auto' : '0 0 auto', minWidth: 0,
              display: 'flex', alignItems: 'center', gap: big ? 10 : 6, maxWidth: big ? 560 : 200, fontSize: big ? 20 : 13, fontWeight: 700,
              color: last ? '#fff' : HF.text2, background: last ? 'rgba(255,255,255,0.16)' : HF.glassFill, border: `1px solid ${HF.glassBorder}` }}>
              {i === 0 && lead}<span className="hf-ellipsis">{p}</span>
            </div>
          </React.Fragment>
        );
      })}
    </div>
  );
}
// Top row: back · title or breadcrumbs · ⋮ (the folder menu, on a level).
function FdPhoneTop({ title, crumbs, more }) {
  const jf = useJfAccount();
  return (
    <div style={{ position: 'absolute', left: 16, right: 16, top: 18, display: 'flex', alignItems: 'center', gap: 8 }}>
      <GlassCircle icon="arrow_back" />
      {crumbs ? <Crumbs path={crumbs} lead={<AcMark acc={jf} size={16} />} /> : <LargeTitle style={{ marginLeft: 4 }}>{title}</LargeTitle>}
      <div style={{ flex: 1 }} />
      {more && <GlassCircle icon="more_vert" />}
    </div>
  );
}
const FD_FADE = 'linear-gradient(180deg, #000 calc(100% - 40px), transparent 100%)';
function FdScroll({ top, children }) {
  return <div style={{ position: 'absolute', left: 16, right: 16, top, bottom: 160, overflow: 'hidden', WebkitMaskImage: FD_FADE, maskImage: FD_FADE }}>{children}</div>;
}

// Root: title + the account, the filter, pinned folders on top, then all folders.
function PhoneFolders({ filter, pressed, children }) {
  const w = useFolderWords(); const t = useT(); const jf = useJfAccount();
  const hit = (n) => !filter || n.toLowerCase().includes(filter.toLowerCase());
  const pinned = FD_PINNED.filter(hit).map((n) => ROOT_FOLDERS.find((f) => f[0] === n));
  const rest = ROOT_FOLDERS.filter((f) => !FD_PINNED.includes(f[0]) && hit(f[0]));
  const row = ([name, , n], i, list, pin) => (
    <FolderRow key={name} name={name} art={FD_ART[name] || null} pinned={pin} match={filter} pressed={pressed === name}
      sub={`${w.nFolders(n)} · ${w.nTracks(FD_TRACKS[name])}`} last={i === list.length - 1} />
  );
  return (
    <Frame kind="phone" tint="#5A3E8C" glowY="12%">
      <FdPhoneTop title={w.folders} />
      <div style={{ position: 'absolute', left: 74, top: 62, display: 'flex', alignItems: 'center', gap: 6 }}>
        <AcMark acc={jf} size={16} /><Txt t="caption" color={HF.text2}>{jf.name} · {w.nFolders(ROOT_FOLDERS.length)}</Txt>
      </div>
      <Field value={filter} placeholder={w.filter} icon="search" style={{ position: 'absolute', left: 16, right: 16, top: 96 }} />
      <FdScroll top={160}>
        <SectionCaps style={{ margin: '4px 4px 0' }}>{filter ? t(`Pinned · ${pinned.length}`, `Закреплённые · ${pinned.length}`) : w.pinned}</SectionCaps>
        <div style={{ padding: '0 4px' }}>{pinned.map((f, i, l) => row(f, i, l, true))}</div>
        <SectionCaps style={{ margin: '14px 4px 0' }}>{filter ? t(`Other folders · ${rest.length}`, `Остальные · ${rest.length}`) : `${w.all} · ${ROOT_FOLDERS.length}`}</SectionCaps>
        <div style={{ padding: '0 4px' }}>{rest.map((f, i, l) => row(f, i, l, false))}</div>
        {filter && <Txt t="caption" color={HF.text3} style={{ margin: '14px 4px 0' }}>{t('The filter searches this level only; pinned folders stay on top.', 'Фильтр ищет только на этом уровне; закреплённые остаются сверху.')}</Txt>}
      </FdScroll>
      <PhoneChrome active="library" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />
      {children}
    </Frame>
  );
}

// Folder header on a level: art (or placeholder), name, counts; Play / Shuffle.
function FdLevelHead({ name, art, sub }) {
  const w = useFolderWords();
  return (
    <React.Fragment>
      <div style={{ display: 'flex', gap: 14, alignItems: 'center', marginBottom: 14 }}>
        <FdArt name={name} art={art} size={96} radius={16} />
        <div style={{ minWidth: 0 }}>
          <div style={{ fontSize: 22, lineHeight: '27px', fontWeight: 800 }}>{name}</div>
          <Txt t="caption" color={HF.text2} style={{ marginTop: 6 }}>{sub}</Txt>
        </div>
      </div>
      <div style={{ display: 'flex', gap: 10 }}>
        <MetalPill icon="play_arrow" style={{ flex: 1 }}>{w.play}</MetalPill>
        <GlassPill icon="shuffle" style={{ flex: 1 }}>{w.shuffle}</GlassPill>
      </div>
    </React.Fragment>
  );
}
// A level: subfolders (pinned first), then the loose tracks of this folder.
function PhoneFolderLevel() {
  const w = useFolderWords(); const t = useT();
  const name = 'Мельница & Хелависа - альбомы';
  const pinned = ALBUMS[3], rest = ALBUMS.filter((a) => a !== pinned).slice(0, 5);
  return (
    <Frame kind="phone" tint="#2E5A3A" glowY="12%">
      <FdPhoneTop crumbs={[w.folders, name]} more />
      <div style={{ position: 'absolute', left: 16, right: 16, top: 76 }}>
        <FdLevelHead name={name} art={null} sub={`${w.nFolders(8)} · ${w.nTracks(97)} · 6 ${t('h', 'ч')} 41 ${t('min', 'мин')}`} />
        <Field placeholder={w.filterHere} icon="search" style={{ marginTop: 12 }} />
      </div>
      <FdScroll top={316}>
        <div style={{ padding: '0 4px' }}>
          <FolderRow name={pinned[0]} art={FD_ART[pinned[0]] || null} pinned sub={w.nTracks(pinned[2])} />
          {rest.map(([n, , k], i) => <FolderRow key={n} name={n} art={FD_ART[n] || null} sub={w.nTracks(k)} last={i === rest.length - 1} />)}
        </div>
        <SectionCaps style={{ margin: '12px 4px 0' }}>{w.tracks} · 2</SectionCaps>
        <div style={{ padding: '0 4px' }}>
          {[['Белая кобыла (демо)', '4:18'], ['Ночная кобыла (акустика)', '3:57']].map(([a, d]) => (
            <TrackRow key={a} title={a} artist="Мельница" hue="green" duration={d} />
          ))}
        </div>
      </FdScroll>
      <PhoneChrome active="library" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />
    </Frame>
  );
}

// An album folder (has art): tracks by disc / track number; long paths collapse to "…".
function PhoneFolderAlbum() {
  const w = useFolderWords(); const t = useT();
  return (
    <Frame kind="phone" tint="#2E5A3A" glowY="12%">
      <FdPhoneTop crumbs={[w.folders, '…', '2009 — Дикие травы']} more />
      <div style={{ position: 'absolute', left: 16, right: 16, top: 76 }}>
        <FdLevelHead name="2009 — Дикие травы" art="green" sub={`${w.nTracks(13)} · 54:10 · FLAC`} />
        <Field placeholder={w.filterHere} icon="search" style={{ marginTop: 12 }} />
      </div>
      <FdScroll top={316}>
        <div style={{ padding: '0 4px' }}>
          {TRACKS.map(([title, d], i) => (
            <div key={title} style={{ display: 'flex', alignItems: 'center', gap: 12, height: 54, borderBottom: i === TRACKS.length - 1 ? 'none' : `1px solid ${HF.hairline}` }}>
              <Txt t="body" color={HF.text3} style={{ width: 22, textAlign: 'right' }}>{i + 1}</Txt>
              <Txt t="rowTitle" ellipsis style={{ flex: 1 }}>{title}</Txt>
              <Txt t="caption" color={HF.text3}>{d}</Txt><Icon name="more_vert" size={22} color={HF.text2} />
            </div>
          ))}
        </div>
      </FdScroll>
      <div style={{ position: 'absolute', left: 16, right: 16, top: 18, pointerEvents: 'none' }}>
        <PlNote icon="touch_app" style={{ position: 'absolute', left: 54, top: 44 }}>{t('Tap a crumb = jump to that level', 'Нажатие на звено = переход на уровень')}</PlNote>
      </div>
      <PhoneChrome active="library" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />
    </Frame>
  );
}

// The folder menu (hold a folder, or ⋮ on its level): the album menu's look and order, plus Shuffle and Pin.
function FdMenu({ name, art, sub, big, pinned }) {
  const w = useFolderWords();
  const c = big ? 84 : 56;
  return (
    <AmMenu big={big} provider="jellyfin" title={name} sub={sub} shuffle note={null}
      cover={<FdArt name={name} art={art} size={c} radius={big ? 16 : 12} />}
      extra={[[pinned ? 'keep_off' : 'keep', pinned ? w.unpin : w.pin]]} />
  );
}
// Phone landscape (960×540 dp drawn at 2× on a 1920×1080 board, as pl-land-*): the title row carries the account and
// the filter; pinned folders keep a column of their own on the left, all folders flow in two columns; tabs and the
// mini player share one bottom row (the dock). Inside a folder the same split holds: head + Play / Shuffle left.
function PhoneFoldersLand() {
  const w = useFolderWords(); const jf = useJfAccount();
  const W = 960, H = 540, S = FRAMES.land.w / W;
  const pinned = FD_PINNED.map((n) => ROOT_FOLDERS.find((f) => f[0] === n));
  const rest = ROOT_FOLDERS.filter((f) => !FD_PINNED.includes(f[0]));
  const half = Math.ceil(rest.length / 2);
  const row = ([name, , n], i, list, pin) => (
    <FolderRow key={name} name={name} art={FD_ART[name] || null} pinned={pin}
      sub={`${w.nFolders(n)} · ${w.nTracks(FD_TRACKS[name])}`} last={i === list.length - 1} />
  );
  const fade = 'linear-gradient(180deg, #000 calc(100% - 30px), transparent 100%)';
  const col = { flex: 1, minWidth: 0 };
  return (
    <div style={{ width: FRAMES.land.w, height: FRAMES.land.h, overflow: 'hidden' }}>
      <div style={{ width: W, height: H, transform: `scale(${S})`, transformOrigin: '0 0' }}>
        <PlFrame w={W} h={H} tint="#5A3E8C" glowY="10%">
          <div style={{ position: 'absolute', left: 24, right: 24, top: 14, display: 'flex', alignItems: 'center', gap: 12 }}>
            <GlassCircle icon="arrow_back" />
            <LargeTitle style={{ flex: '0 0 auto' }}>{w.folders}</LargeTitle>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 6, minWidth: 0 }}>
              <AcMark acc={jf} size={16} /><Txt t="caption" color={HF.text2} ellipsis>{jf.name} · {w.nFolders(ROOT_FOLDERS.length)}</Txt>
            </div>
            <div style={{ flex: 1 }} />
            <Field placeholder={w.filter} icon="search" style={{ width: 300 }} />
          </div>
          <div style={{ position: 'absolute', left: 24, right: 24, top: 76, bottom: 92, display: 'flex', gap: 24, overflow: 'hidden', WebkitMaskImage: fade, maskImage: fade }}>
            <div style={{ width: 280, flex: '0 0 auto' }}>
              <SectionCaps style={{ margin: '0 4px' }}>{w.pinned}</SectionCaps>
              <div style={{ padding: '0 4px' }}>{pinned.map((f, i, l) => row(f, i, l, true))}</div>
            </div>
            <div style={{ width: 1, background: HF.hairline, flex: '0 0 auto' }} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <SectionCaps style={{ margin: '0 4px' }}>{`${w.all} · ${ROOT_FOLDERS.length}`}</SectionCaps>
              <div style={{ display: 'flex', gap: 20, padding: '0 4px' }}>
                <div style={col}>{rest.slice(0, half).map((f, i, l) => row(f, i, l, false))}</div>
                <div style={col}>{rest.slice(half).map((f, i, l) => row(f, i, l, false))}</div>
              </div>
            </div>
          </div>
          <HmDock sit={HM_SIT.c} active="library" mini="queue" />
        </PlFrame>
      </div>
    </div>
  );
}

function PhoneFolderMenu() {
  const w = useFolderWords();
  const name = 'Мельница & Хелависа - концерты';
  return (
    <PhoneFolders pressed={name}>
      <Scrim opacity={0.35} />
      <Sheet height={470}>
        <FdMenu name={name} art={null} sub={`${w.folder} · ${w.nFolders(5)} · ${w.nTracks(71)}`} />
      </Sheet>
    </PhoneFolders>
  );
}

// Placeholder spec: art vs placeholder at the three sizes in use.
function FdCovers() {
  const t = useT();
  const names = ['Мельница & Хелависа - альбомы', 'RC - Sorted', 'Детские', 'Metallica (mp3)', 'Fleur - Дискография (2000-2026)'];
  return (
    <div className="hf" style={{ position: 'relative', width: 980, height: 520, overflow: 'hidden', background: HF.ground }}>
      <Backdrop tint="#5A3E8C" glowY="0%">
        <div style={{ position: 'absolute', inset: 0, padding: '28px 32px' }}>
          <Txt t="dialogTitle">{t('Folder art · placeholder when a folder has none', 'Обложка папки · заглушка, если её нет')}</Txt>
          <Txt t="caption" color={HF.text2} style={{ marginTop: 6, maxWidth: 900 }}>
            {t('Art = the folder image, else the first album’s cover inside. Without either: a steel plate with a drawn folder and the monogram of the name (the part before " - ", initials of up to two words), faintly tinted by the name — the same name always gets the same tint.',
              'Обложка = картинка папки, иначе обложка первого альбома внутри. Если нет ни того ни другого — стальная пластина с нарисованной папкой и монограммой названия (часть до « - », инициалы до двух слов), слегка подкрашенная по названию: одно название — всегда один цвет.')}
          </Txt>
          <div style={{ display: 'flex', gap: 22, alignItems: 'flex-end', marginTop: 22 }}>
            <div><FdArt name="Pink Floyd" art="amber" size={220} radius={24} /><Txt t="caption" color={HF.text2} style={{ marginTop: 6 }}>{t('Art · car 220', 'Обложка · авто 220')}</Txt></div>
            <div><FdPlaceholder name={names[0]} size={220} radius={24} /><Txt t="caption" color={HF.text2} style={{ marginTop: 6 }}>{t('Placeholder · car 220', 'Заглушка · авто 220')}</Txt></div>
            <div><FdPlaceholder name={names[1]} size={112} radius={18} /><Txt t="caption" color={HF.text2} style={{ marginTop: 6 }}>{t('Home tile 112', 'Плитка Главной 112')}</Txt></div>
            <div><FdPlaceholder name={names[2]} size={96} radius={16} /><Txt t="caption" color={HF.text2} style={{ marginTop: 6 }}>{t('Level head 96', 'Заголовок 96')}</Txt></div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              {names.slice(2).map((n) => <div key={n} style={{ display: 'flex', alignItems: 'center', gap: 10 }}><FdPlaceholder name={n} size={48} /><Txt t="caption" color={HF.text2}>{n} → {fdMonogram(n)}</Txt></div>)}
            </div>
          </div>
        </div>
      </Backdrop>
    </div>
  );
}

// ── Car ──────────────────────────────────────────────────────────────────────
// Tile tap = open; the knob = play the folder (1 tap). `mark` = pinned / recent glyph; `pressed` = held (ringed).
function FolderTile({ name, hue, art, sub, w = 220, coverH = 190, mark, font = 20, pressed }) {
  return (
    <div style={{ width: w, flex: '0 0 auto' }}>
      <div style={{ position: 'relative', borderRadius: 24, boxShadow: pressed ? `0 0 0 4px ${HF.accent}` : 'none' }}>
        {art !== undefined ? <FdArt name={name} art={art} size={w} radius={24} height={coverH} /> : <Cover size={w} hue={hue} radius={24} glyph="folder" style={{ height: coverH }} />}
        {mark && <div style={{ position: 'absolute', left: 10, top: 10, width: 36, height: 36, borderRadius: 18, background: 'rgba(5,6,10,0.55)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}><Icon name={mark} size={20} /></div>}
        <Knob icon="play_arrow" size={64} style={{ position: 'absolute', right: 10, bottom: 10 }} />
      </div>
      <div className="hf-ellipsis" style={{ fontSize: font, lineHeight: `${font + 6}px`, fontWeight: 700, marginTop: 8 }}>{name}</div>
      {sub && <div className="hf-ellipsis" style={{ fontSize: font - 4, color: HF.text2 }}>{sub}</div>}
    </div>
  );
}
function NowPlayingCapsule({ width = 440 }) {
  return (
    <div style={{ height: 64, width, borderRadius: 32, background: HF.barFill, border: `1px solid ${HF.glassBorder}`, display: 'flex', alignItems: 'center', gap: 12, padding: '0 8px', flex: '0 0 auto' }}>
      <Cover size={48} hue="amber" radius={24} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="hf-ellipsis" style={{ fontSize: 18, fontWeight: 700 }}>Wish You Were Here</div>
        <div className="hf-ellipsis" style={{ fontSize: 14, color: HF.text2 }}>Pink Floyd</div>
      </div>
      <Knob icon="pause" size={48} /><Icon name="skip_next" size={32} style={{ margin: '0 8px' }} />
    </div>
  );
}

// Folders tab of the car Library: pinned first (pin mark), then A–Z; most root folders show the placeholder.
function CarFolders({ side = 'left', pressed, menu }) {
  const w = useFolderWords();
  const pinned = FD_PINNED.map((n) => ROOT_FOLDERS.find((f) => f[0] === n));
  const rest = ROOT_FOLDERS.filter((f) => !FD_PINNED.includes(f[0]));
  const tiles = [...pinned.map((f) => [...f, 'keep']), ...rest];
  return (
    <CbShell side={side} tab="folders" provider="jellyfin" dim={!!menu} overlay={menu} rail={['F', 'L', 'M', 'P', 'Q', 'R', 'T', 'А', 'Д', 'М', 'С', 'Ч']}>
      {tiles.map(([name, , n, mark]) => (
        <FolderTile key={name} name={name} art={FD_ART[name] || null} mark={mark} pressed={pressed === name}
          sub={`${w.nFolders(n)} · ${w.nTracks(FD_TRACKS[name])}`} w={CB.tile} coverH={CB.tile} font={21} />
      ))}
    </CbShell>
  );
}
// Hold a folder tile → the folder menu in car sizes, on the wheel side.
function CarFolderMenu({ side = 'left' }) {
  const w = useFolderWords();
  const name = 'Мельница & Хелависа - альбомы';
  return (
    <CarFolders side={side} pressed={name} menu={
      <SidePanel width={720} side={side}>
        <FdMenu big name={name} art={null} pinned sub={`${w.folder} · ${w.nFolders(8)} · ${w.nTracks(97)}`} />
      </SidePanel>} />
  );
}
// Inside a folder: breadcrumbs in the top row; the wheel side holds the folder's head and big actions; the
// subfolders (pinned first) fill the far side.
function CarFolderLevel({ side = 'left' }) {
  const w = useFolderWords(); const t = useT();
  const name = 'Мельница & Хелависа - альбомы';
  const tiles = [ALBUMS[3], ...ALBUMS.filter((a) => a !== ALBUMS[3])];
  const colW = 470;
  const at = (left, width) => (side === 'left' ? { left } : { left: FRAMES.head.w - left - width });
  return (
    <CbShell side={side} crumbs={[w.folders, name]} provider="jellyfin" gridLeft={48 + colW + 40} overlay={
      <div style={{ position: 'absolute', top: 124, width: colW, ...at(48, colW) }}>
        <div style={{ display: 'flex', gap: 20, alignItems: 'center' }}>
          <FdPlaceholder name={name} size={132} radius={22} />
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 30, lineHeight: '36px', fontWeight: 800 }}>{name}</div>
            <div style={{ fontSize: 19, color: HF.text2, marginTop: 6 }}>{w.nFolders(8)} · {w.nTracks(97)}</div>
          </div>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14, marginTop: 26 }}>
          <MetalPill icon="play_arrow" height={84} style={{ fontSize: 23 }}>{w.play}</MetalPill>
          <GlassPill icon="shuffle" height={84} style={{ fontSize: 23 }}>{w.shuffle}</GlassPill>
          <GlassPill icon="stacks" height={84} style={{ fontSize: 23 }}>{w.addTo}</GlassPill>
          <GlassPill icon="keep_off" height={84} style={{ fontSize: 23 }}>{w.unpin}</GlassPill>
        </div>
        <div style={{ fontSize: 17, color: HF.text3, marginTop: 18 }}>{t('Hold a folder for Play next / Add to end.', 'Удержание папки — «Играть следующим» / «В конец».')}</div>
      </div>}>
      {tiles.map(([n, , k], i) => <FolderTile key={n} name={n} art={FD_ART[n] || null} mark={i === 0 ? 'keep' : undefined} sub={w.nTracks(k)} w={CB.tile} coverH={CB.tile} font={21} />)}
    </CbShell>
  );
}

// Superseded (kept for history): the old car home with pinned / recent folders.
function CarHomeFolders() {
  const w = useFolderWords();
  const tiles = [['Pink Floyd', 'amber', 'keep'], ['Мельница & Хелависа - альбомы', 'green', 'keep'], ['RC - Sorted', 'teal', 'keep'],
    ['Аквариум и БГ (mp3)', 'green', 'history'], ['Детские', 'rose', 'history']];
  const far = (
    <React.Fragment>
      <div style={{ position: 'absolute', left: 880, top: 338, ...HFType.sectionCaps, fontSize: 15, color: HF.text2 }}>{w.pinned} · {w.recent}</div>
      <div style={{ position: 'absolute', left: 880, top: 372, display: 'flex', gap: 18 }}>
        {tiles.map(([name, hue, mark]) => <FolderTile key={name} name={name} hue={hue} mark={mark} w={182} coverH={150} font={18} />)}
      </div>
    </React.Fragment>
  );
  return <Frame kind="head" tint="#8A5A22" glowY="40%"><CarHome far={far} /></Frame>;
}

Object.assign(window, { PhoneFolders, PhoneFoldersLand, PhoneFolderLevel, PhoneFolderAlbum, PhoneFolderMenu, FdCovers, CarFolders, CarFolderLevel, CarFolderMenu,
  CarHomeFolders, FolderTile, FolderRow, Crumbs, NowPlayingCapsule, ROOT_FOLDERS, FD_ART, FD_PINNED, FD_TRACKS, FdArt, FdPlaceholder, FdMenu,
  useFolderWords, fdPl });
