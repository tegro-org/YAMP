// albummenu.jsx — Album / artist actions (tasks/todo.md "Album / artist actions", user 2026-11-17).
// A components-and-screens module like jellyfin.jsx: no sections of its own. albumMenuBoards() returns the variants,
// each placed next to the board it extends (YAMP.html adds them to sections 4, 5, 10 and 11b).
// One menu for an album or an artist, from a long press wherever they are listed and from a ⋮ in the album / artist
// page header: Play now (replaces the queue) · Play next · Add to end · Add to queue… · Start radio (Yandex
// `album:` / `artist:` stations; hidden for Jellyfin until Phase 6). Adding an artist asks first: all albums in
// release order or the popular tracks, singles and compilations optional, counts shown.
// Every top-level name here starts with `Am` (the canvas scripts share one global scope).

// ── Sample data (neutral) ────────────────────────────────────────────────────
const AM_ARTIST = { title: 'Мельница', hue: 'teal' };
// In release order, as the artist page lists them.
const AM_ALBUMS = [['Перевал', '2005', 'teal', 12], ['Зов крови', '2006', 'rose', 14], ['Дикие травы', '2009', 'green', 12],
  ['Ангелофрения', '2012', 'blue', 13], ['Алхимия', '2015', 'amber', 11]];
const AM_ALBUM_TRACKS = [['Травушка', '4:05'], ['Ветер', '3:51'], ['Огонь', '4:37'], ['Голубка', '3:12'], ['Лето', '5:20'], ['Княже', '4:44']];

function AmUseWords() {
  const t = useT();
  return {
    playNow: t('Play now', 'Слушать сейчас'), playNowSub: t('Replaces the current queue', 'Заменит текущую очередь'),
    playNext: t('Play next', 'Играть следующим'), addEnd: t('Add to end of queue', 'Добавить в конец очереди'),
    addTo: t('Add to queue…', 'Добавить в очередь…'), radio: t('Start radio', 'Запустить радио'),
    addPlaylist: t('Add to playlist…', 'Добавить в плейлист…'), shuffle: t('Shuffle', 'Перемешать'),
    album: t('Album', 'Альбом'), artist: t('Artist', 'Исполнитель'),
    asksFirst: t('Adding an artist asks first: all albums or the popular tracks.', 'Перед добавлением исполнителя спросим: все альбомы или популярное.'),
    noRadio: t('Playlists and radio for Jellyfin come later (Phases 5–6).', 'Плейлисты и радио для Jellyfin появятся позже (этапы 5–6).'),
  };
}

// ── The menu (phone sheet content or car panel content) ─────────────────────
// `kind` album | artist, `provider` yandex | jellyfin, `big` = car sizes (large targets).
// Folder use (folders.jsx): `cover` replaces the cover, `shuffle` adds Shuffle after Play now, `extra` = rows
// [icon, label] after the queue actions, `note` overrides the footnote (null = none).
function AmMenu({ kind = 'album', provider = 'yandex', title, sub, hue, big, badge, cover: coverNode, shuffle, extra, note: noteText }) {
  const w = AmUseWords();
  const rowH = big ? 84 : 56, icon = big ? 34 : 24, font = big ? 25 : 17, cover = big ? 84 : 56;
  const Row = ({ icon: name, label, note }) => (
    <div style={{ display: 'flex', alignItems: 'center', gap: big ? 24 : 18, minHeight: rowH }}>
      <Icon name={name} size={icon} fill={0} />
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: font, lineHeight: `${font + 6}px` }}>{label}</div>
        {note && <div style={{ fontSize: big ? 17 : 13, lineHeight: big ? '22px' : '17px', color: HF.text3 }}>{note}</div>}
      </div>
    </div>
  );
  return (
    <div style={{ padding: big ? '22px 32px 0' : '18px 24px 0' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: big ? 20 : 14, marginBottom: big ? 12 : 8 }}>
        {coverNode || <Cover size={cover} hue={hue} radius={kind === 'artist' ? cover / 2 : big ? 16 : 12} glyph={kind === 'artist' ? 'person' : undefined} />}
        <div style={{ minWidth: 0 }}>
          <div className="hf-ellipsis" style={big ? { fontSize: 30, lineHeight: '36px', fontWeight: 800 } : HFType.dialogTitle}>{title}</div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 2 }}>
            {badge && <ProviderBadge provider={provider} size={big ? 20 : 15} />}
            <div className="hf-ellipsis" style={{ fontSize: big ? 19 : 13, color: HF.text2 }}>{sub}</div>
          </div>
        </div>
      </div>
      <Row icon="play_arrow" label={w.playNow} note={w.playNowSub} />
      {shuffle && <Row icon="shuffle" label={w.shuffle} />}
      <Row icon="skip_next" label={w.playNext} />
      <Row icon="playlist_play" label={w.addEnd} />
      <Row icon="stacks" label={w.addTo} />
      {provider === 'yandex' && <Row icon="playlist_add" label={w.addPlaylist} />}
      {provider === 'yandex' && <Row icon="sensors" label={w.radio} />}
      {(extra || []).map(([icon, label]) => <Row key={icon} icon={icon} label={label} />)}
      {noteText !== null && (noteText || kind === 'artist' || provider === 'jellyfin') && (
        <div style={{ fontSize: big ? 17 : 13, lineHeight: big ? '22px' : '17px', color: HF.text3, marginTop: big ? 10 : 6 }}>
          {noteText || (kind === 'artist' ? w.asksFirst : w.noRadio)}
        </div>
      )}
    </div>
  );
}

// ── Section 10 · search results with a long press ───────────────────────────
// Today's search list (albums strip, artists strip) with the pressed item ringed, then the sheet.
function AmPressed({ children, round }) {
  return <div style={{ borderRadius: round ? '50%' : 20, boxShadow: `0 0 0 3px ${HF.accent}`, display: 'inline-block' }}>{children}</div>;
}
function AmSearchBg({ pressed }) {
  const t = useT(); const w = SrUseWords(); const d = SrUseData();
  const tile = (a, i, round) => {
    const size = round ? 96 : 132;
    const c = <Cover size={size} hue={a.hue} radius={round ? size / 2 : 18} glyph={round ? 'person' : undefined} />;
    return (
      <div key={i} style={{ width: round ? 104 : size, flex: '0 0 auto' }}>
        <div style={{ textAlign: 'center' }}>{pressed === (round ? 'artist' : 'album') + i ? <AmPressed round={round}>{c}</AmPressed> : c}</div>
        <Txt t="cardTitle" ellipsis style={{ marginTop: 8, textAlign: round ? 'center' : 'left' }}>{a.title}</Txt>
        <SrSub provider={a.p} center={round}>{a.sub}</SrSub>
      </div>
    );
  };
  return (
    <React.Fragment>
      <div style={{ position: 'absolute', left: 16, right: 8, top: 20, display: 'flex', alignItems: 'center', gap: 4 }}>
        <SrField value="мельница" /><TextAction>{w.cancel}</TextAction>
      </div>
      <SrPills style={{ position: 'absolute', left: 0, right: 0, top: 84 }} />
      <div style={{ position: 'absolute', left: 16, right: 16, top: 138 }}>
        <SrCaps first>{w.artists}</SrCaps>
        <div style={{ display: 'flex', gap: 12, marginRight: -16, overflow: 'hidden' }}>{d.artists.map((a, i) => tile(a, i, true))}</div>
        <SrCaps>{w.albums}</SrCaps>
        <div style={{ display: 'flex', gap: 12, marginRight: -16, overflow: 'hidden' }}>{d.albums.slice(0, 4).map((a, i) => tile(a, i, false))}</div>
        <Txt t="caption" color={HF.text3} style={{ margin: '14px 4px 0' }}>{t('Tap — open · hold — actions', 'Нажатие — открыть · удержание — действия')}</Txt>
      </div>
      <PhoneChrome active="search" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />
    </React.Fragment>
  );
}
function AmSearchMenu({ kind = 'album', provider = 'yandex' }) {
  const t = useT(); const w = AmUseWords();
  // Pressed items in the merged sample list: albums[1] is Yandex «Дикие травы», albums[0] the Jellyfin one; artists[1] Yandex «Мельница».
  const pressed = kind === 'artist' ? 'artist1' : provider === 'jellyfin' ? 'album0' : 'album1';
  const title = kind === 'artist' ? AM_ARTIST.title : 'Дикие травы';
  const sub = kind === 'artist' ? `${w.artist} · ${t('14 albums', '14 альбомов')}` : `${w.album} · Мельница · 2009`;
  return (
    <Frame kind="phone">
      <AmSearchBg pressed={pressed} />
      <Scrim opacity={0.35} />
      <Sheet height={kind === 'artist' ? 470 : provider === 'yandex' ? 440 : 410}>
        <AmMenu kind={kind} provider={provider} title={title} sub={sub} hue={kind === 'artist' ? 'teal' : 'green'} badge />
      </Sheet>
    </Frame>
  );
}

// ── Section 11b · album and artist pages with the ⋮ in the header ───────────
// The ⋮ is ringed where the board is about it (the new header button); not when an album row is held.
function AmTopBar({ ring = true }) {
  return (
    <div style={{ position: 'absolute', left: 16, right: 16, top: 22, display: 'flex', justifyContent: 'space-between' }}>
      <GlassCircle icon="arrow_back" />
      <div style={{ borderRadius: 22, boxShadow: ring ? `0 0 0 2px ${HF.accent}` : 'none' }}><GlassCircle icon="more_vert" /></div>
    </div>
  );
}
function AmPageHead({ title, sub, hue, round }) {
  return (
    <div style={{ display: 'flex', gap: 14, alignItems: 'center', marginBottom: 16 }}>
      <Cover size={104} hue={hue} radius={round ? 52 : 16} glyph={round ? 'person' : undefined} />
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: 24, lineHeight: '29px', fontWeight: 800 }}>{title}</div>
        <Txt t="caption" color={HF.text2} style={{ marginTop: 6 }}>{sub}</Txt>
      </div>
    </div>
  );
}
function AmPlayRow() {
  const t = useT();
  return (
    <div style={{ display: 'flex', gap: 10 }}>
      <MetalPill icon="play_arrow" style={{ flex: 1 }}>{t('Play', 'Слушать')}</MetalPill>
      <GlassPill icon="shuffle" style={{ flex: 1 }}>{t('Shuffle', 'Перемешать')}</GlassPill>
    </div>
  );
}
function AmAlbumPage({ menu }) {
  const t = useT(); const w = AmUseWords();
  return (
    <Frame kind="phone" tint="#2E5A3A">
      <AmTopBar />
      <div style={{ position: 'absolute', left: 16, right: 16, top: 84 }}>
        <AmPageHead title="Дикие травы" sub={t('Мельница · 2009 · 12 tracks · 49 min', 'Мельница · 2009 · 12 треков · 49 мин')} hue="green" />
        <AmPlayRow />
        <SectionCaps style={{ margin: '20px 4px 4px' }}>{t('Tracks', 'Треки')}</SectionCaps>
        <div style={{ padding: '0 4px' }}>
          {AM_ALBUM_TRACKS.map(([title, d], i) => (
            <div key={title} style={{ display: 'flex', alignItems: 'center', gap: 12, height: 52, borderBottom: i === AM_ALBUM_TRACKS.length - 1 ? 'none' : `1px solid ${HF.hairline}` }}>
              <Txt t="body" color={HF.text3} style={{ width: 22, textAlign: 'right' }}>{i + 1}</Txt>
              <Txt t="rowTitle" ellipsis style={{ flex: 1 }}>{title}</Txt>
              <Txt t="caption" color={HF.text3}>{d}</Txt><Icon name="more_vert" size={22} color={HF.text2} />
            </div>
          ))}
        </div>
      </div>
      <PhoneChrome active="library" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />
      {menu && <Scrim opacity={0.35} />}
      {menu && <Sheet height={440}><AmMenu title="Дикие травы" sub={`${w.album} · Мельница · 2009`} hue="green" /></Sheet>}
    </Frame>
  );
}
// Artist page: popular tracks, then the albums in release order; every album row has the same hold menu.
function AmArtistPage({ menu, dim }) {
  const t = useT(); const w = AmUseWords();
  return (
    <React.Fragment>
      <AmTopBar ring={menu !== 'album' && !dim} />
      <div style={{ position: 'absolute', left: 16, right: 16, top: 84 }}>
        {menu !== 'album' && (
          <React.Fragment>
            <AmPageHead title={AM_ARTIST.title} sub={t('14 albums · 6 singles', '14 альбомов · 6 синглов')} hue="teal" round />
            <AmPlayRow />
            <SectionCaps style={{ margin: '18px 4px 2px' }} trailing={<TextAction style={{ padding: '0 8px' }}>{t('All', 'Все')}</TextAction>}>{t('Popular', 'Популярное')}</SectionCaps>
            {[['Ночная кобыла', 'teal'], ['Дороги', 'teal']].map(([a, hue]) => <TrackRow key={a} title={a} artist={AM_ARTIST.title} hue={hue} />)}
          </React.Fragment>
        )}
        <SectionCaps style={{ margin: menu === 'album' ? '0 4px 2px' : '14px 4px 2px' }}>{t('Albums · release order', 'Альбомы · по дате выхода')}</SectionCaps>
        {(menu === 'album' ? AM_ALBUMS : AM_ALBUMS.slice(0, 4)).map(([title, year, hue, n]) => {
          const pressed = menu === 'album' && title === 'Алхимия';
          return (
            <div key={title} style={{ display: 'flex', alignItems: 'center', gap: 12, height: 64, borderRadius: 14, padding: '0 4px',
              boxShadow: pressed ? `0 0 0 2px ${HF.accent}` : 'none' }}>
              <Cover size={48} hue={hue} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <Txt t="rowTitle" ellipsis>{title}</Txt>
                <Txt t="caption" color={HF.text2}>{t(`${year} · ${n} tracks`, `${year} · ${n} треков`)}</Txt>
              </div>
              <Icon name="more_vert" size={22} color={HF.text2} />
            </div>
          );
        })}
      </div>
      <PhoneChrome active="library" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />
      {(menu || dim) && <Scrim opacity={dim ? 0.55 : 0.35} />}
      {menu === 'artist' && <Sheet height={470}><AmMenu kind="artist" title={AM_ARTIST.title} sub={`${w.artist} · ${t('14 albums', '14 альбомов')}`} hue="teal" /></Sheet>}
      {menu === 'album' && <Sheet height={440}><AmMenu title="Алхимия" sub={`${w.album} · Мельница · 2015`} hue="amber" /></Sheet>}
    </React.Fragment>
  );
}

// The artist confirmation. `choice` albums | popular, `singles` on/off, `action` add | play.
function AmOption({ on, title, count, children }) {
  return (
    <div style={{ borderRadius: 16, padding: '12px 14px', marginTop: 10, border: `1px solid ${on ? 'rgba(255,255,255,0.45)' : HF.glassBorder}`,
      background: on ? HF.glassFill : 'transparent' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <Icon name={on ? 'radio_button_checked' : 'radio_button_unchecked'} size={22} color={on ? '#fff' : HF.text2} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 16, fontWeight: 700 }}>{title}</div>
          <div style={{ fontSize: 13, color: HF.text2, marginTop: 2 }}>{count}</div>
        </div>
      </div>
      {children}
    </div>
  );
}
function AmArtistConfirm({ choice = 'albums', singles, action = 'add' }) {
  const t = useT();
  const albumsCount = singles ? t('20 releases · 171 tracks', '20 релизов · 171 трек') : t('14 albums · 147 tracks', '14 альбомов · 147 треков');
  const title = action === 'play' ? t(`Play ${AM_ARTIST.title}`, `Слушать «${AM_ARTIST.title}»`) : t(`Add ${AM_ARTIST.title}`, `Добавить «${AM_ARTIST.title}»`);
  const target = action === 'play' ? t('Replaces the current queue «With family».', 'Заменит текущую очередь «С семьёй».')
    : t('Goes to the end of «With family».', 'В конец очереди «С семьёй».');
  return (
    <Frame kind="phone" tint="#24505A">
      <AmArtistPage dim />
      <Dialog width={364} title={title} text={target}
        actions={<React.Fragment><DialogAction>{t('Cancel', 'Отмена')}</DialogAction><DialogAction kind="primary">{action === 'play' ? t('Play', 'Слушать') : t('Add', 'Добавить')}</DialogAction></React.Fragment>}>
        <AmOption on={choice === 'albums'} title={t('All albums · release order', 'Все альбомы · по дате выхода')} count={albumsCount}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 12, paddingLeft: 34, opacity: choice === 'albums' ? 1 : 0.4 }}>
            <div style={{ flex: 1, fontSize: 14, color: HF.text }}>{t('Include singles and compilations', 'Добавить синглы и сборники')}</div>
            <Switch on={!!singles} />
          </div>
        </AmOption>
        <AmOption on={choice === 'popular'} title={t('Popular tracks', 'Популярные треки')} count={t('Popular · 20 tracks', 'Популярное · 20 треков')} />
      </Dialog>
    </Frame>
  );
}

// ── Section 4 · Add to queue… from an album: the account's queues, "New queue" on top ─
function AmAlbumAddToQueue() {
  const t = useT(); const a = useAcAccounts();
  const queues = [
    ...useAcQueues().filter((q) => q.id === 'a-family').map((q) => ({ ...q, active: false })),
    { id: 'a-work', acc: a.anna, kind: 'static', name: t('For work', 'Для работы'), track: 'Shine On', artist: 'Pink Floyd', hue: 'blue', pos: 2, total: 31, progress: 0.12 },
  ];
  return (
    <Frame kind="phone">
      <Scrim opacity={0.2} />
      <Sheet height={560}>
        <QueueSheetHeader title={t('Add to queue', 'Добавить в очередь')} subtitle={t('Дикие травы · Мельница · 12 tracks', 'Дикие травы · Мельница · 12 треков')} />
        <div style={{ padding: '0 8px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, height: 64, borderRadius: 18, padding: '0 14px 0 12px', margin: '0 0 6px',
            background: HF.glassFill, border: `1px solid ${HF.glassBorder}` }}>
            <div style={{ width: 52, height: 52, borderRadius: 14, display: 'flex', alignItems: 'center', justifyContent: 'center', border: `1px dashed ${HF.glassBorder}` }}>
              <Icon name="add" size={26} />
            </div>
            <div style={{ flex: 1 }}>
              <Txt t="rowTitle">{t('New queue', 'Новая очередь')}</Txt>
              <Txt t="caption" color={HF.text2}>{t(`On ${a.anna.name} · named after the album`, `В аккаунте ${a.anna.name} · по названию альбома`)}</Txt>
            </div>
          </div>
          <AcHeader acc={a.anna} />
          {queues.map((q) => <AcRow key={q.id} q={q} />)}
        </div>
        <Txt t="caption" color={HF.text3} style={{ margin: '12px 20px 0' }}>
          {t(`Only ${a.anna.name}’s queues: a queue holds music of its own account (D84). My Wave and radios fill themselves.`,
            `Только очереди аккаунта ${a.anna.name}: в очереди — музыка её аккаунта (D84). Волна и радио наполняются сами.`)}
        </Txt>
      </Sheet>
    </Frame>
  );
}

// ── Add to playlist… (Yandex): after the artist dialog (or straight from an album), pick a playlist ─────────
function AmAddToPlaylist() {
  const t = useT(); const a = useAcAccounts();
  const lists = [
    [t('Road trip', 'В дорогу'), t('48 tracks', '48 треков'), 'amber'],
    [t('Evening', 'Вечер'), t('23 tracks', '23 трека'), 'violet'],
    [t('Russian rock', 'Русский рок'), t('112 tracks', '112 треков'), 'rose'],
    [t('Chill', 'Спокойное'), t('37 tracks', '37 треков'), 'teal'],
  ];
  return (
    <Frame kind="phone">
      <Scrim opacity={0.2} />
      <Sheet height={600}>
        <QueueSheetHeader title={t('Add to playlist', 'Добавить в плейлист')} subtitle={t('Мельница · all albums · 147 tracks', 'Мельница · все альбомы · 147 треков')} />
        <div style={{ padding: '0 8px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, height: 64, borderRadius: 18, padding: '0 14px 0 12px', margin: '0 0 6px',
            background: HF.glassFill, border: `1px solid ${HF.glassBorder}` }}>
            <div style={{ width: 52, height: 52, borderRadius: 14, display: 'flex', alignItems: 'center', justifyContent: 'center', border: `1px dashed ${HF.glassBorder}` }}>
              <Icon name="add" size={26} />
            </div>
            <div style={{ flex: 1 }}>
              <Txt t="rowTitle">{t('New playlist', 'Новый плейлист')}</Txt>
              <Txt t="caption" color={HF.text2}>{t(`On ${a.anna.name} · named after the artist`, `В аккаунте ${a.anna.name} · по имени исполнителя`)}</Txt>
            </div>
          </div>
          {lists.map(([n, c, hue]) => (
            <div key={n} style={{ display: 'flex', alignItems: 'center', gap: 12, height: 64, padding: '0 14px 0 12px' }}>
              <Cover size={52} hue={hue} radius={12} glyph="queue_music" />
              <div style={{ flex: 1, minWidth: 0 }}><Txt t="rowTitle" ellipsis>{n}</Txt><Txt t="caption" color={HF.text2}>{c}</Txt></div>
            </div>
          ))}
        </div>
        <Txt t="caption" color={HF.text3} style={{ margin: '12px 20px 0' }}>
          {t(`${a.anna.name}’s playlists (the account of the music). Yandex only for now.`, `Плейлисты аккаунта ${a.anna.name} (аккаунта этой музыки). Пока только Яндекс.`)}
        </Txt>
      </Sheet>
    </Frame>
  );
}

// ── Section 5 · car: hold an album tile → the same menu, car sizes ──────────
function AmCarFar() {
  const t = useT();
  return (
    <div style={{ position: 'absolute', left: 860, top: 70, width: 1020 }}>
      <div style={{ ...HFType.sectionCaps, fontSize: 15, color: HF.text2, marginBottom: 14 }}>{t('Albums', 'Альбомы')}</div>
      <div style={{ display: 'flex', gap: 24 }}>
        {AM_ALBUMS.slice(1, 5).map(([title, year, hue]) => (
          <div key={title} style={{ width: 220 }}>
            <div style={{ borderRadius: 26, boxShadow: title === 'Дикие травы' ? `0 0 0 4px ${HF.accent}` : 'none' }}><Cover size={220} hue={hue} radius={26} /></div>
            <div className="hf-ellipsis" style={{ fontSize: 22, fontWeight: 700, marginTop: 10 }}>{title}</div>
            <div style={{ fontSize: 17, color: HF.text2 }}>Мельница · {year}</div>
          </div>
        ))}
      </div>
    </div>
  );
}
function AmCarMenu() {
  const w = AmUseWords();
  return (
    <Frame kind="head" tint="#2E5A3A" glowY="40%">
      <CarHome dim far={<AmCarFar />} />
      <SidePanel width={720} side="left">
        <AmMenu big title="Дикие травы" sub={`${w.album} · Мельница · 2009`} hue="green" />
      </SidePanel>
    </Frame>
  );
}

// ── Boards, keyed by the section they join (`after` = the board they follow) ─
function albumMenuBoards() {
  const P = FRAMES.phone, H = FRAMES.head;
  return {
    search: [
      { after: 'sr-results-more', boards: [
        { id: 'am-search-album', label: 'Album / artist actions · hold an album in results · Play now · Play next · Add to end · Add to queue… · Add to playlist… · Start radio', w: P.w, h: P.h, el: <AmSearchMenu kind="album" /> },
        { id: 'am-search-artist', label: 'Hold an artist · same menu; adding asks first (all albums / popular)', w: P.w, h: P.h, el: <AmSearchMenu kind="artist" /> },
        { id: 'am-search-album-jf', label: 'Hold a Jellyfin album · no Add to playlist / Start radio yet (Phases 5–6)', w: P.w, h: P.h, el: <AmSearchMenu kind="album" provider="jellyfin" /> },
      ] },
    ],
    'home-library': [
      { after: 'jf-playlist', boards: [
        { id: 'am-album-page', label: 'Album page · ⋮ in the header opens the album menu', w: P.w, h: P.h, el: <AmAlbumPage /> },
        { id: 'am-album-page-menu', label: 'Album page · ⋮ opened', w: P.w, h: P.h, el: <AmAlbumPage menu /> },
        { id: 'am-artist-page-menu', label: 'Artist page · ⋮ opened (artist menu)', w: P.w, h: P.h, el: <Frame kind="phone" tint="#24505A"><AmArtistPage menu="artist" /></Frame> },
        { id: 'am-artist-album-menu', label: 'Artist page · hold an album in the list → the album menu', w: P.w, h: P.h, el: <Frame kind="phone" tint="#24505A"><AmArtistPage menu="album" /></Frame> },
        { id: 'am-artist-confirm-add', label: 'Add an artist (Add to end / Play next / Add to queue…) · all albums, singles off, counts', w: P.w, h: P.h, el: <AmArtistConfirm /> },
        { id: 'am-artist-confirm-singles', label: 'Add an artist · singles and compilations included · counts update', w: P.w, h: P.h, el: <AmArtistConfirm singles /> },
        { id: 'am-artist-confirm-popular', label: 'Add an artist · popular tracks chosen', w: P.w, h: P.h, el: <AmArtistConfirm choice="popular" /> },
        { id: 'am-artist-confirm-play', label: 'Play an artist now · same choice, replaces the queue', w: P.w, h: P.h, el: <AmArtistConfirm action="play" /> },
        { id: 'am-add-to-playlist', label: 'Add to playlist… (Yandex) · after the artist dialog (or from an album): the account’s playlists, "New playlist" on top; hidden for Jellyfin', w: P.w, h: P.h, el: <AmAddToPlaylist /> },
      ] },
    ],
    'queues-phone': [
      { after: 'jf-add-to-queue', boards: [
        { id: 'am-add-to-queue', label: 'Add to queue… from an album · the account’s queues, "New queue" on top (D84)', w: P.w, h: P.h, el: <AmAlbumAddToQueue /> },
      ] },
    ],
    'queues-car': [
      { after: 'jfc-panel-flat', boards: [
        { id: 'amc-album-menu', label: 'Car mode · hold an album tile → the album menu, car sizes', w: H.w, h: H.h, el: <AmCarMenu /> },
      ] },
    ],
  };
}

Object.assign(window, { AmAddToPlaylist, AmMenu, AmSearchMenu, AmAlbumPage, AmArtistPage, AmArtistConfirm, AmAlbumAddToQueue, AmCarMenu, albumMenuBoards });
