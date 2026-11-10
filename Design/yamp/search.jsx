// search.jsx — 10 · Merged search (Phase 3). One result list for all signed-in providers (D21 / ARCHITECTURE Q6).
// Exposes searchSections(): the canvas sections of this file, each
// { id, title, subtitle, boards: [{ id, label, w, h, el }] } — wired into YAMP.html's buildSections().
//
// Rules shown here:
// - Results of every provider are interleaved by each provider's own rank, starting with the provider that is
//   first in Settings (here Jellyfin) — so the same song from two providers lands in two neighbouring rows.
// - Provider badge (Y / J) at the start of the subtitle line, only while the visible list mixes providers.
// - Filter pills exist only for the types the signed-in providers have (no Jellyfin → no "Folders").
// - Typing is a parked-car action: the head unit has search on the Search tab only, never in car mode.

const SrQuery = 'мельница';
const SrAllTypes = ['all', 'tracks', 'albums', 'artists', 'folders', 'playlists'];
const SrNoFolders = ['all', 'tracks', 'albums', 'artists', 'playlists'];

function SrUseWords() {
  const t = useT();
  return {
    all: t('All', 'Все'), tracks: t('Tracks', 'Треки'), albums: t('Albums', 'Альбомы'), artists: t('Artists', 'Исполнители'),
    folders: t('Folders', 'Папки'), playlists: t('Playlists', 'Плейлисты'),
    top: t('Top result', 'Лучший результат'), artist: t('Artist', 'Исполнитель'), playlist: t('Playlist', 'Плейлист'),
    cancel: t('Cancel', 'Отмена'), hint: t('Tracks, artists, albums', 'Треки, артисты, альбомы'),
    search: t('Search', 'Поиск'),
  };
}

// Mock results for the query, already in merged order (J, Y, J, Y…).
function SrUseData() {
  const t = useT();
  return {
    artists: [
      { title: 'Мельница', p: 'jellyfin', hue: 'green', sub: t('8 albums', '8 альбомов') },
      { title: 'Мельница', p: 'yandex', hue: 'teal', sub: t('14 albums', '14 альбомов') },
      { title: 'Хелависа', p: 'jellyfin', hue: 'rose', sub: t('3 albums', '3 альбома') },
      { title: 'Хелависа', p: 'yandex', hue: 'violet', sub: t('5 albums', '5 альбомов') },
    ],
    tracks: [
      { title: 'Дороги', sub: 'Мельница · Перевал', p: 'jellyfin', hue: 'teal' },
      { title: 'Дороги', sub: 'Мельница · Перевал', p: 'yandex', hue: 'teal' },
      { title: 'Невеста Полоза', sub: 'Мельница · Зов крови', p: 'jellyfin', hue: 'rose' },
      { title: 'Ночная кобыла', sub: 'Мельница · Перевал', p: 'yandex', hue: 'teal' },
      { title: 'Княже', sub: 'Мельница · Дикие травы', p: 'jellyfin', hue: 'green' },
      { title: 'Двери Тамерлана', sub: 'Мельница · Зов крови', p: 'yandex', hue: 'rose' },
    ],
    albums: [
      { title: 'Дикие травы', sub: 'Мельница · 2009', p: 'jellyfin', hue: 'green' },
      { title: 'Дикие травы', sub: 'Мельница · 2009', p: 'yandex', hue: 'green' },
      { title: 'Перевал', sub: 'Мельница · 2005', p: 'jellyfin', hue: 'teal' },
      { title: 'Алхимия', sub: 'Мельница · 2015', p: 'yandex', hue: 'amber' },
      { title: 'Зов крови', sub: 'Мельница · 2006', p: 'jellyfin', hue: 'rose' },
      { title: 'Ангелофрения', sub: 'Мельница · 2012', p: 'yandex', hue: 'blue' },
    ],
    folders: [
      { title: 'Мельница & Хелависа - альбомы', sub: t('8 folders · 97 tracks', '8 папок · 97 треков'), p: 'jellyfin', hue: 'green' },
      { title: 'Мельница & Хелависа - концерты', sub: t('5 folders · 71 tracks', '5 папок · 71 трек'), p: 'jellyfin', hue: 'teal' },
      { title: 'Мельница — Лучшее', sub: t('in RC - Sorted · 18 tracks', 'в RC - Sorted · 18 треков'), p: 'jellyfin', hue: 'violet' },
      { title: 'Мельница (акустика)', sub: t('in Мельница & Хелависа - концерты · 12 tracks', 'в Мельница & Хелависа - концерты · 12 треков'), p: 'jellyfin', hue: 'amber' },
    ],
    playlists: [
      { title: t('Мельница — favourites', 'Мельница — избранное'), sub: t('Playlist · 42 tracks', 'Плейлист · 42 трека'), p: 'jellyfin', hue: 'green' },
      { title: t('Мельница: the best', 'Мельница: лучшее'), sub: t('Playlist · 30 tracks', 'Плейлист · 30 треков'), p: 'yandex', hue: 'amber' },
      { title: t('Russian folk rock', 'Русский фолк-рок'), sub: t('Playlist · 120 tracks', 'Плейлист · 120 треков'), p: 'yandex', hue: 'violet' },
    ],
  };
}
// What Yandex Music alone returns: no duplicates, no badges.
function SrSolo(list) {
  const seen = {};
  const own = list.filter((x) => x.p === 'yandex').concat(list.filter((x) => x.p !== 'yandex'));
  return own.filter((x) => (seen[x.title] ? false : (seen[x.title] = true))).map((x) => ({ ...x, p: null }));
}

// ── Pieces ───────────────────────────────────────────────────────────────────
// Today's search field: a dark inset pill. `big` = car size.
function SrField({ value, placeholder, big, history, style }) {
  const h = big ? 60 : 52;
  return (
    <div style={{ height: h, borderRadius: h / 2, padding: big ? '0 8px 0 22px' : '0 6px 0 18px', display: 'flex', alignItems: 'center', gap: big ? 14 : 12, flex: 1, minWidth: 0,
      background: 'rgba(0,0,0,0.35)', border: `1px solid ${HF.glassBorder}`, boxShadow: 'inset 0 2px 6px rgba(0,0,0,0.45)', ...style }}>
      <Icon name="search" size={big ? 28 : 22} color={HF.text2} />
      <div className="hf-ellipsis" style={{ flex: 1, fontSize: big ? 22 : 17, color: value ? HF.text : HF.text3 }}>{value || placeholder}</div>
      {value && <div style={{ width: big ? 56 : 44, height: big ? 56 : 44, display: 'flex', alignItems: 'center', justifyContent: 'center' }}><Icon name="close" size={big ? 28 : 20} color={HF.text2} /></div>}
      {history && (
        <React.Fragment>
          <div style={{ width: 1, height: 28, background: HF.glassBorder }} />
          <div style={{ width: 44, height: 44, display: 'flex', alignItems: 'center', justifyContent: 'center' }}><Icon name="history" size={22} /></div>
        </React.Fragment>
      )}
    </div>
  );
}

// Type filter. The row scrolls sideways; `shift` = how far it is scrolled.
function SrPills({ active = 'all', types = SrAllTypes, big, shift = 0, pad = 16, style }) {
  const w = SrUseWords();
  const car = big ? { height: 56, borderRadius: 28, padding: '0 22px', fontSize: 19 } : undefined;
  return (
    <div style={{ overflow: 'hidden', ...style }}>
      <div style={{ display: 'flex', gap: big ? 10 : 8, marginLeft: pad - shift, whiteSpace: 'nowrap' }}>
        {types.map((id) => <FilterPill key={id} active={id === active} style={car}>{w[id]}</FilterPill>)}
      </div>
    </div>
  );
}

// Subtitle line; the provider badge leads it when the list mixes providers.
function SrSub({ provider, children, size = 13, badge = 15, center }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: center ? 'center' : 'flex-start', gap: Math.round(badge * 0.4), minWidth: 0 }}>
      {provider && <ProviderBadge provider={provider} size={badge} />}
      <div className="hf-ellipsis" style={{ fontSize: size, lineHeight: `${size + 4}px`, color: HF.text2, minWidth: 0 }}>{children}</div>
    </div>
  );
}

function SrCaps({ children, big, first }) {
  if (big) return <div className="hf-ellipsis" style={{ ...HFType.sectionCaps, fontSize: 15, color: HF.text2, height: 28 }}>{children}</div>;
  return <SectionCaps style={{ margin: first ? '0 4px 10px' : '18px 4px 10px' }}>{children}</SectionCaps>;
}

function SrTop({ item, big, chevron = true }) {
  const w = SrUseWords();
  const cover = big ? 84 : 60;
  return (
    <div style={{ height: big ? 116 : 88, borderRadius: big ? 28 : 24, padding: big ? '0 20px 0 16px' : '0 12px 0 14px', display: 'flex', alignItems: 'center', gap: big ? 20 : 14,
      background: `linear-gradient(100deg, ${HF.steelLight} 0%, ${HF.steelDark} 72%)`, border: `1px solid ${HF.glassBorder}`, boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.14)' }}>
      <Cover size={cover} hue={item.hue} radius={cover / 2} glyph="person" />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="hf-ellipsis" style={{ fontSize: big ? 32 : 22, lineHeight: big ? '40px' : '28px', fontWeight: 800 }}>{item.title}</div>
        <SrSub provider={item.p} size={big ? 18 : 14} badge={big ? 20 : 16}>{w.artist} · {item.sub}</SrSub>
      </div>
      {chevron && <Icon name="chevron_right" size={big ? 34 : 24} color={HF.text2} />}
    </div>
  );
}

function SrHeart() {
  return (
    <div style={{ width: 44, height: 44, borderRadius: 22, flex: '0 0 auto', background: HF.glassFill, border: `1px solid ${HF.glassBorder}`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <Icon name="favorite" size={22} fill={0} />
    </div>
  );
}

// One result row. Phone 64 dp, car (`big`) 78 dp. `kind`: track | artist | album | folder | playlist.
function SrRow({ item, kind = 'track', big, line, last }) {
  const cover = big ? 60 : 48;
  const trailing = kind === 'folder'
    ? <React.Fragment>{big ? <Knob icon="play_arrow" size={56} /> : <GlassCircle icon="play_arrow" size={40} />}{!big && <Icon name="chevron_right" size={22} color={HF.text3} />}</React.Fragment>
    : kind === 'track' ? (big ? null : <SrHeart />) : (big ? null : <Icon name="chevron_right" size={22} color={HF.text3} />);
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: big ? 16 : 12, height: big ? 78 : 64 }}>
      <Cover size={cover} hue={item.hue} radius={kind === 'artist' ? cover / 2 : big ? 14 : HFShape.cover}
        glyph={kind === 'folder' ? 'folder' : kind === 'artist' ? 'person' : kind === 'playlist' ? 'queue_music' : undefined} />
      <div style={{ flex: 1, minWidth: 0, alignSelf: 'stretch', display: 'flex', alignItems: 'center', gap: 8, borderBottom: line && !last ? `1px solid ${HF.hairline}` : 'none' }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="hf-ellipsis" style={big ? { fontSize: 22, lineHeight: '28px', fontWeight: 700 } : HFType.rowTitle}>{item.title}</div>
          <SrSub provider={item.p} size={big ? 16 : 13} badge={big ? 18 : 15}>{item.sub}</SrSub>
        </div>
        {trailing}
      </div>
    </div>
  );
}

// Side-scrolling strips of the "All" view, as today: round artists, square albums.
function SrStrip({ list, round }) {
  const size = round ? 96 : 132;
  return (
    <div style={{ display: 'flex', gap: 12, marginRight: -16, overflow: 'hidden' }}>
      {list.map((a, i) => (
        <div key={i} style={{ width: round ? 104 : size, flex: '0 0 auto' }}>
          <Cover size={size} hue={a.hue} radius={round ? size / 2 : 18} glyph={round ? 'person' : undefined} style={{ margin: '0 auto' }} />
          <Txt t="cardTitle" ellipsis style={{ marginTop: 8, textAlign: round ? 'center' : 'left' }}>{a.title}</Txt>
          <SrSub provider={a.p} center={round}>{a.sub}</SrSub>
        </div>
      ))}
    </div>
  );
}

// "One provider did not answer": quiet, above the results of the others.
function SrBanner({ big }) {
  const t = useT();
  return (
    <div style={{ minHeight: big ? 64 : 52, borderRadius: big ? 20 : 16, padding: big ? '8px 8px 8px 20px' : '6px 4px 6px 14px', display: 'flex', alignItems: 'center', gap: big ? 14 : 10,
      background: HF.glassFill, border: `1px solid ${HF.hairline}` }}>
      <Icon name="cloud_off" size={big ? 26 : 20} color={HF.warning} />
      <div style={{ flex: 1, minWidth: 0, fontSize: big ? 18 : 13, lineHeight: big ? '23px' : '17px', color: HF.text2 }}>
        {t('Jellyfin did not answer — showing Yandex Music only', 'Jellyfin не ответил — показана только Яндекс Музыка')}
      </div>
      <TextAction style={big ? { height: 56, padding: '0 14px', fontSize: 19 } : { padding: '0 10px' }}>{t('Retry', 'Повторить')}</TextAction>
    </div>
  );
}

// ── Phone ────────────────────────────────────────────────────────────────────
function SrPhone({ query = SrQuery, types, active, shift, children }) {
  const w = SrUseWords();
  return (
    <Frame kind="phone">
      <div style={{ position: 'absolute', left: 16, right: 8, top: 20, display: 'flex', alignItems: 'center', gap: 4 }}>
        <SrField value={query} /><TextAction>{w.cancel}</TextAction>
      </div>
      <SrPills types={types} active={active} shift={shift} style={{ position: 'absolute', left: 0, right: 0, top: 84 }} />
      <div style={{ position: 'absolute', left: 16, right: 16, top: 138 }}>{children}</div>
      <PhoneChrome active="search" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />
    </Frame>
  );
}

// 1 · Nothing typed: as today — field, history button → recent searches over the provider's charts.
function SrPhoneIdle() {
  const t = useT(); const w = SrUseWords();
  const card = (title, sub, hue, size) => (
    <div key={title} style={{ width: size, flex: '0 0 auto' }}>
      <Cover size={size} hue={hue} radius={18} />
      <Txt t="cardTitle" ellipsis style={{ marginTop: 8 }}>{title}</Txt><Txt t="caption" color={HF.text2} ellipsis>{sub}</Txt>
    </div>
  );
  const recent = ['мельница', 'pink floyd', 'дикие травы', 'wish you were here', 'сплин'];
  return (
    <Frame kind="phone">
      <LargeTitle style={{ position: 'absolute', left: 20, top: 22 }}>{w.search}</LargeTitle>
      <div style={{ position: 'absolute', left: 16, right: 16, top: 148 }}>
        <SectionCaps style={{ margin: '0 4px 10px' }}>{t('Hits of the week', 'Хиты недели')}</SectionCaps>
        <div style={{ display: 'flex', gap: 12, marginRight: -16, overflow: 'hidden' }}>
          {card(t('First song', 'Первая песня'), t('The Band', 'Группа'), 'blue', 150)}
          {card(t('A very long song title', 'Очень длинное название трека'), t('Artist with a long name', 'Исполнитель с длинным именем'), 'rose', 150)}
          {card(t('Third song', 'Третья песня'), t('Artist', 'Исполнитель'), 'green', 150)}
        </div>
        <SectionCaps style={{ margin: '18px 4px 10px' }}>{t('Trending', 'В тренде')}</SectionCaps>
        <div style={{ display: 'flex', gap: 12 }}>
          {card(t('Fourth song', 'Четвёртая песня'), t('The Band', 'Группа'), 'violet', 184)}
          {card(t('Fifth song', 'Пятая песня'), t('Artist', 'Исполнитель'), 'amber', 184)}
        </div>
      </div>
      <PhoneChrome active="search" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />
      <Scrim opacity={0.55} />
      <div style={{ position: 'absolute', left: 16, right: 16, top: 72, display: 'flex' }}><SrField placeholder={w.hint} history /></div>
      <div style={{ position: 'absolute', left: 16, right: 16, top: 134, background: HF.sheet, borderRadius: 24, border: `1px solid ${HF.glassBorder}`,
        boxShadow: '0 18px 50px rgba(0,0,0,0.55)', padding: '6px 6px 8px 18px' }}>
        <SectionCaps trailing={<TextAction style={{ padding: '0 12px' }}>{t('Clear', 'Очистить')}</TextAction>}>{t('Recent searches', 'Недавние запросы')}</SectionCaps>
        {recent.map((q) => (
          <div key={q} style={{ display: 'flex', alignItems: 'center', gap: 14, height: 52 }}>
            <Icon name="history" size={22} color={HF.text2} />
            <Txt t="rowTitle" ellipsis style={{ flex: 1, fontWeight: 400 }}>{q}</Txt>
            <div style={{ width: 44, height: 44, display: 'flex', alignItems: 'center', justifyContent: 'center' }}><Icon name="close" size={20} color={HF.text2} /></div>
          </div>
        ))}
      </div>
    </Frame>
  );
}

// 2 / 4 / 5 · "All": top result, artists, tracks. `solo` = one provider only (no badges, no Folders pill).
function SrPhoneResults({ solo, partial }) {
  const w = SrUseWords(); const d = SrUseData();
  const one = solo || partial;
  const artists = one ? SrSolo(d.artists) : d.artists, tracks = one ? SrSolo(d.tracks) : d.tracks;
  return (
    <SrPhone types={solo ? SrNoFolders : SrAllTypes}>
      {partial && <div style={{ marginBottom: 16 }}><SrBanner /></div>}
      <SrCaps first>{w.top}</SrCaps>
      <SrTop item={artists[0]} />
      <SrCaps>{w.artists}</SrCaps>
      <SrStrip list={artists} round />
      <SectionCaps style={{ margin: '18px 4px 4px' }}>{w.tracks}</SectionCaps>
      <div style={{ padding: '0 4px' }}>
        {tracks.slice(0, partial ? 3 : 4).map((x, i) => <SrRow key={i} item={x} />)}
      </div>
    </SrPhone>
  );
}

// 2b · The same list further down: albums, folders, playlists.
function SrPhoneResultsMore() {
  const w = SrUseWords(); const d = SrUseData();
  return (
    <SrPhone>
      <SrCaps first>{w.albums}</SrCaps>
      <SrStrip list={d.albums.slice(0, 4)} />
      <SectionCaps style={{ margin: '18px 4px 4px' }}>{w.folders}</SectionCaps>
      <div style={{ padding: '0 4px' }}>
        {d.folders.slice(0, 2).map((x, i) => <SrRow key={i} item={x} kind="folder" line last={i === 1} />)}
      </div>
      <SectionCaps style={{ margin: '18px 4px 4px' }}>{w.playlists}</SectionCaps>
      <div style={{ padding: '0 4px' }}>
        {d.playlists.map((x, i) => <SrRow key={i} item={x} kind="playlist" line last={i === d.playlists.length - 1} />)}
      </div>
    </SrPhone>
  );
}

// 3 · One type. Folders exist in Jellyfin only → the list does not mix providers → no badges.
function SrPhoneFolders() {
  const t = useT(); const w = SrUseWords(); const d = SrUseData();
  return (
    <SrPhone active="folders" shift={170}>
      <SectionCaps style={{ margin: '0 4px 4px' }}>{w.folders} · {d.folders.length}</SectionCaps>
      <div style={{ padding: '0 4px' }}>
        {d.folders.map((x, i) => <FolderRow key={i} name={x.title} hue={x.hue} sub={x.sub} last={i === d.folders.length - 1} />)}
      </div>
      <Txt t="caption" color={HF.text3} style={{ margin: '14px 4px 0' }}>
        {t('Row — open the folder, play button — play all of it. Folders are searched in Jellyfin only.', 'Строка — открыть папку, кнопка — слушать её целиком. Папки ищутся только в Jellyfin.')}
      </Txt>
    </SrPhone>
  );
}

// 6 · Nothing found in any provider.
function SrPhoneEmpty() {
  const t = useT();
  return (
    <SrPhone query={t('qwertyuiop', 'йцукенгшщз')}>
      <div style={{ marginTop: 150, display: 'flex', flexDirection: 'column', alignItems: 'center', textAlign: 'center', padding: '0 20px' }}>
        <div style={{ width: 88, height: 88, borderRadius: 44, background: HF.glassFill, border: `1px solid ${HF.glassBorder}`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name="search_off" size={42} color={HF.text2} />
        </div>
        <Txt t="dialogTitle" style={{ marginTop: 18 }}>{t('Nothing found', 'Ничего не найдено')}</Txt>
        <Txt t="body" color={HF.text2} style={{ marginTop: 8 }}>
          {t('Neither Yandex Music nor Jellyfin has anything for this query. Check the spelling or try other words.', 'Ни в Яндекс Музыке, ни в Jellyfin по этому запросу ничего нет. Проверьте написание или попробуйте другие слова.')}
        </Txt>
      </div>
    </SrPhone>
  );
}

// ── Head unit 1920×720 (parked) ──────────────────────────────────────────────
// The Search tab in landscape: the width is used for one column per type; rows are 78 dp.
function SrCarColumn({ title, w = 400, children }) {
  return <div style={{ width: w, flex: '0 0 auto' }}><SrCaps big>{title}</SrCaps>{children}</div>;
}
function SrCarResults() {
  const w = SrUseWords(); const d = SrUseData();
  return (
    <Frame kind="head" glowY="20%">
      <div style={{ position: 'absolute', left: 48, right: 0, top: 24, display: 'flex', alignItems: 'center', gap: 16 }}>
        <GlassCircle icon="arrow_back" size={60} />
        <SrField big value={SrQuery} style={{ flex: '0 0 620px' }} />
        <SrPills big pad={4} style={{ flex: 1 }} />
      </div>
      <div style={{ position: 'absolute', left: 48, top: 110, display: 'flex', gap: 32 }}>
        <SrCarColumn title={w.top} w={410}>
          <SrTop item={d.artists[0]} big chevron={false} />
          <div style={{ height: 20 }} />
          <SrCaps big>{w.artists}</SrCaps>
          {d.artists.map((x, i) => <SrRow key={i} item={x} kind="artist" big />)}
        </SrCarColumn>
        <SrCarColumn title={w.tracks}>{d.tracks.map((x, i) => <SrRow key={i} item={x} big />)}</SrCarColumn>
        <SrCarColumn title={w.albums}>{d.albums.map((x, i) => <SrRow key={i} item={x} kind="album" big />)}</SrCarColumn>
        <SrCarColumn title={w.folders} w={518}>
          {d.folders.slice(0, 3).map((x, i) => <SrRow key={i} item={x} kind="folder" big />)}
          <div style={{ height: 20 }} />
          <SrCaps big>{w.playlists}</SrCaps>
          {d.playlists.slice(0, 2).map((x, i) => <SrRow key={i} item={x} kind="playlist" big />)}
        </SrCarColumn>
      </div>
      <Dock active="search" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />
    </Frame>
  );
}

// Slim split pane 640×720: one column, car-sized rows.
function SrSplitResults() {
  const w = SrUseWords(); const d = SrUseData();
  return (
    <Frame kind="splitN" glowY="20%">
      <div style={{ position: 'absolute', left: 28, right: 28, top: 24, display: 'flex', alignItems: 'center', gap: 14 }}>
        <GlassCircle icon="arrow_back" size={60} /><SrField big value={SrQuery} />
      </div>
      <SrPills big pad={28} style={{ position: 'absolute', left: 0, right: 0, top: 100 }} />
      <div style={{ position: 'absolute', left: 28, right: 28, top: 178 }}>
        <SrCaps big>{w.top}</SrCaps>
        <SrTop item={d.artists[0]} big />
        <div style={{ height: 18 }} />
        <SrCaps big>{w.tracks}</SrCaps>
        {d.tracks.slice(0, 3).map((x, i) => <SrRow key={i} item={x} big />)}
      </div>
      <div style={{ position: 'absolute', left: 28, bottom: 20 }}><NowPlayingCapsule width={584} /></div>
    </Frame>
  );
}

function searchSections() {
  const P = FRAMES.phone, H = FRAMES.head, N = FRAMES.splitN;
  return [
    { id: 'search', title: '10 · Merged search (Phase 3)',
      subtitle: 'One result list for every signed-in provider (D21 / Q6): results are interleaved by rank, the same song from two providers stays two rows. The Y / J badge appears only while the list mixes providers (DESIGN_GUIDE §8). Typing is a parked-car action (§5): the head unit has search on the Search tab only, never in car mode.',
      boards: [
        { id: 'sr-idle', label: 'Nothing typed · recent searches (as today)', w: P.w, h: P.h, el: <SrPhoneIdle /> },
        { id: 'sr-results', label: 'Results · both providers, badges, duplicate song', w: P.w, h: P.h, el: <SrPhoneResults /> },
        { id: 'sr-results-more', label: 'Results · scrolled: albums, folders, playlists', w: P.w, h: P.h, el: <SrPhoneResultsMore /> },
        { id: 'sr-folders', label: 'Filter “Folders” · Jellyfin only, opens the folder browser', w: P.w, h: P.h, el: <SrPhoneFolders /> },
        { id: 'sr-single', label: 'One provider signed in · no badges, no Folders', w: P.w, h: P.h, el: <SrPhoneResults solo /> },
        { id: 'sr-partial', label: 'Jellyfin did not answer · banner + Retry', w: P.w, h: P.h, el: <SrPhoneResults partial /> },
        { id: 'sr-empty', label: 'No results', w: P.w, h: P.h, el: <SrPhoneEmpty /> },
        { id: 'sr-head', label: 'Head unit 1920×720 · results in columns (parked)', w: H.w, h: H.h, el: <SrCarResults /> },
        { id: 'sr-split', label: 'Slim pane 640×720 · results in one column (parked)', w: N.w, h: N.h, el: <SrSplitResults /> },
      ] },
  ];
}

Object.assign(window, { searchSections });
