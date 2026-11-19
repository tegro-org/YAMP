// carbrowse.jsx — (f) Car album view and the car Library shell (tasks/todo.md "gaps after v0.5.0", 2026-11-19).
// The new car screen is the player and has no tiles bar, so browsing needs one way in. Proposed: the player's ⋮ →
// "Library" (last item, user 2026-11-19) opens the car Library of the active queue's account, on the tab used last:
//   Jellyfin — Folders · Albums · Playlists · Favourites;  Yandex — Albums · Playlists · Liked.
// Back (or the now-playing capsule) returns to the player. Tile tap = open; hold = the album menu in car sizes on the
// wheel side (albummenu.jsx `AmMenu big`). The A–Z rail sits on the wheel side; the text filter opens only when
// parked (no typing while driving). Everything mirrors for the right-hand wheel.
// Alternatives (not drawn): a Library button in the player's top row (costs the queue dropdown's room); separate ⋮
// items "Albums" / "Folders" (a longer menu, two places to keep in step); a swipe from the far edge (not discoverable).
// Every top-level name here starts with `Cb` / `CB` (the canvas scripts share one global scope).

const CB = { w: FRAMES.head.w, h: FRAMES.head.h, pad: 48, top: 124, tile: 230, gap: 24 };

const CB_ALBUMS_Y = [['A Night at the Opera', 'Queen', 'rose'], ['Abbey Road', 'The Beatles', 'blue'], ['Animals', 'Pink Floyd', 'teal'],
  ['Innuendo', 'Queen', 'violet'], ['Master of Puppets', 'Metallica', 'amber'], ['The Wall', 'Pink Floyd', 'gray'],
  ['Wish You Were Here', 'Pink Floyd', 'amber'], ['Алхимия', 'Мельница', 'amber'], ['Гранатовый альбом', 'Сплин', 'violet'],
  ['Дикие травы', 'Мельница', 'green'], ['Зов крови', 'Мельница', 'rose'], ['Ангелофрения', 'Мельница', 'blue']];
const CB_ALBUMS_J = [['Afterglow Avenue', 'Static Bloom', 'rose'], ['Copper Sky', 'Hollow Pines', 'amber'], ['Field Notes', 'Field Notes', 'blue'],
  ['Low Orbit', 'Static Bloom', 'violet'], ['Morning Tide', 'Field Notes', 'teal'], ['Neon Harbour', 'Lumen Lane', 'blue'],
  ['Night Drive', 'Lumen Lane', 'violet'], ['Paper Lanterns', 'The Quiet Coast', 'teal'], ['Slow Signal', 'Lumen Lane', 'gray'],
  ['Small Hours', 'The Quiet Coast', 'green'], ['Tunnel Lights', 'Lumen Lane', 'amber'], ['Winter Coast', 'The Quiet Coast', 'sand']];

function CbUseTabs(provider) {
  const t = useT();
  return provider === 'jellyfin'
    ? [['folders', t('Folders', 'Папки')], ['albums', t('Albums', 'Альбомы')], ['playlists', t('Playlists', 'Плейлисты')], ['liked', t('Favourites', 'Избранное')]]
    : [['albums', t('Albums', 'Альбомы')], ['playlists', t('Playlists', 'Плейлисты')], ['liked', t('Liked', 'Мне нравится')]];
}
function CbTabs({ provider, tab }) {
  const tabs = CbUseTabs(provider);
  return (
    <div style={{ height: 64, borderRadius: 32, padding: 4, display: 'flex', flex: '0 0 auto', background: 'rgba(0,0,0,0.35)', border: `1px solid ${HF.hairline}` }}>
      {tabs.map(([id, label]) => (
        <div key={id} style={{ borderRadius: 28, display: 'flex', alignItems: 'center', padding: '0 22px', fontSize: 21, fontWeight: 700,
          color: id === tab ? '#fff' : HF.text2, background: id === tab ? `linear-gradient(180deg, ${HF.anodLight}, ${HF.anodDark})` : 'transparent',
          boxShadow: id === tab ? 'inset 0 1px 0 rgba(255,255,255,0.18), 0 0 0 1px rgba(201,206,216,0.35)' : 'none' }}>{label}</div>
      ))}
    </div>
  );
}
// The account the browse belongs to (the active queue's); ▾ picks another account.
function CbAccountChip({ provider }) {
  const jf = useJfAccount(); const a = useAcAccounts();
  const acc = provider === 'jellyfin' ? jf : a.anna;
  return (
    <div style={{ height: 60, borderRadius: 30, padding: '0 16px 0 12px', display: 'flex', alignItems: 'center', gap: 10, flex: '0 0 auto',
      background: HF.glassFill, border: `1px solid ${HF.glassBorder}`, fontSize: 20, fontWeight: 700 }}>
      <AcMark acc={acc} size={34} /><span className="hf-ellipsis" style={{ maxWidth: 190 }}>{acc.name}</span><Icon name="expand_more" size={26} color={HF.text2} />
    </div>
  );
}
// A–Z: only the letters present, Latin then Cyrillic, columns of up to seven.
function CbRail({ letters, on = 0, style }) {
  const cols = []; for (let i = 0; i < letters.length; i += 7) cols.push(letters.slice(i, i + 7));
  return (
    <div style={{ position: 'absolute', display: 'flex', gap: 8, ...style }}>
      {cols.map((c, ci) => (
        <div key={ci} style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {c.map((ch, i) => (
            <div key={ch + i} style={{ width: 56, height: 56, borderRadius: 16, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 22, fontWeight: 800,
              background: ci * 7 + i === on ? 'rgba(255,255,255,0.18)' : HF.glassFill, border: `1px solid ${HF.glassBorder}` }}>{ch}</div>
          ))}
        </div>
      ))}
    </div>
  );
}
// The car Library screen. Wheel side: back · tabs (or breadcrumbs inside a folder) · Filter (parked); the A–Z rail.
// Far side: the account chip and the now-playing capsule. Tiles fill the rest and scroll (the cut row shows it).
// `gridLeft` = the grid's start from the wheel-side edge; `overlay` = what lies above (a column, a menu panel).
function CbShell({ side = 'left', tab, provider = 'jellyfin', crumbs, rail, dim, gridLeft, overlay, tint = '#3B3355', children }) {
  const t = useT();
  const railW = rail ? (rail.length > 7 ? 120 : 56) : 0;
  const gl = gridLeft || CB.pad + (rail ? railW + 28 : 0);
  const gw = CB.w - gl - CB.pad;
  const at = (left, width) => (side === 'left' ? { left } : { left: CB.w - left - width });
  const fade = 'linear-gradient(180deg, #000 calc(100% - 70px), transparent 100%)';
  return (
    <PlFrame w={CB.w} h={CB.h} tint={tint} glowY="10%">
      <div style={{ position: 'absolute', top: 26, display: 'flex', alignItems: 'center', gap: 14, flexDirection: side === 'left' ? 'row' : 'row-reverse',
        ...(side === 'left' ? { left: CB.pad } : { right: CB.pad }), maxWidth: 1000 }}>
        <GlassCircle icon="arrow_back" size={60} />
        {crumbs ? <Crumbs big path={crumbs} /> : <CbTabs provider={provider} tab={tab} />}
        {!crumbs && <CarPill icon="search">{t('Filter', 'Фильтр')}</CarPill>}
      </div>
      <div style={{ position: 'absolute', top: 24, display: 'flex', alignItems: 'center', gap: 14, flexDirection: side === 'left' ? 'row' : 'row-reverse',
        ...(side === 'left' ? { right: CB.pad } : { left: CB.pad }) }}>
        <CbAccountChip provider={provider} />
        <NowPlayingCapsule width={380} />
      </div>
      {rail && <CbRail letters={rail} style={{ top: CB.top, ...at(CB.pad, railW) }} />}
      <div style={{ position: 'absolute', top: CB.top, bottom: 0, width: gw, ...at(gl, gw), overflow: 'hidden', WebkitMaskImage: fade, maskImage: fade }}>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: `22px ${CB.gap}px`, paddingTop: 4 }}>{children}</div>
      </div>
      {dim && <Scrim opacity={0.45} />}
      {overlay}
    </PlFrame>
  );
}

function CbAlbumTile({ title, artist, hue, pressed }) {
  return (
    <div style={{ width: CB.tile, flex: '0 0 auto' }}>
      <div style={{ borderRadius: 24, boxShadow: pressed ? `0 0 0 4px ${HF.accent}` : 'none' }}><Cover size={CB.tile} hue={hue} radius={24} /></div>
      <div className="hf-ellipsis" style={{ fontSize: 21, lineHeight: '27px', fontWeight: 700, marginTop: 8 }}>{title}</div>
      <div className="hf-ellipsis" style={{ fontSize: 17, color: HF.text2 }}>{artist}</div>
    </div>
  );
}
// Albums tab. `provider` = the active queue's account; `pressed` = the held tile; `menu` = the album menu open;
// `addTo` = its "Add to queue…" picked (the picker replaces the menu in the same panel).
function CbAlbums({ side = 'left', provider = 'yandex', pressed, menu, addTo }) {
  const w = AmUseWords();
  const list = provider === 'jellyfin' ? CB_ALBUMS_J : CB_ALBUMS_Y;
  const rail = provider === 'jellyfin' ? ['A', 'C', 'F', 'L', 'M', 'N', 'P', 'S', 'T', 'W'] : ['A', 'I', 'M', 'T', 'W', 'А', 'Г', 'Д', 'З'];
  const held = list.find((a) => a[0] === pressed);
  return (
    <CbShell side={side} tab="albums" provider={provider} rail={rail} dim={!!(menu || addTo)} tint={provider === 'jellyfin' ? '#3B3355' : '#2E4A3A'}
      overlay={(menu || addTo) && held && (
        <SidePanel width={720} side={side}>
          {addTo
            ? <CbAddToQueue provider={provider} title={held[0]} artist={held[1]} />
            : <AmMenu big provider={provider} title={held[0]} sub={`${w.album} · ${held[1]}`} hue={held[2]} />}
        </SidePanel>)}>
      {list.map(([a, b, hue]) => <CbAlbumTile key={a} title={a} artist={b} hue={hue} pressed={a === pressed} />)}
    </CbShell>
  );
}

// "Add to queue…" from the car album menu: the queues of the album's account only (D84) — My Wave and radios fill
// themselves, so static queues only; "New queue" on top (no typing in the car: it is named after the album); rows are
// the car switcher's 100 dp rows. Back returns to the album menu, ✕ closes both. A tap adds and closes with a toast.
function CbAddToQueue({ provider = 'yandex', title, artist }) {
  const t = useT(); const a = useAcAccounts(); const jf = useJfAccount();
  const acc = provider === 'jellyfin' ? jf : a.anna;
  const queues = provider === 'jellyfin'
    ? useJfQueues().filter((q) => q.acc.id === 'jf').map((q) => ({ ...q, active: false }))
    : [
        ...useAcQueues().filter((q) => q.id === 'a-family').map((q) => ({ ...q, active: false })),
        { id: 'a-work', acc: a.anna, kind: 'static', name: t('For work', 'Для работы'), track: 'Shine On', artist: 'Pink Floyd', hue: 'blue', pos: 2, total: 31, progress: 0.12 },
        { id: 'a-road', acc: a.anna, kind: 'static', name: t('Road trip', 'В дорогу'), track: 'Дороги', artist: 'Мельница', hue: 'green', pos: 9, total: 64, progress: 0.66 },
      ];
  const n = provider === 'jellyfin' ? 11 : 12;
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, padding: '24px 24px 14px' }}>
        <GlassCircle icon="arrow_back" size={60} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 34, lineHeight: '40px', fontWeight: 800 }}>{t('Add to queue', 'Добавить в очередь')}</div>
          <div className="hf-ellipsis" style={{ fontSize: 18, color: HF.text2, marginTop: 2 }}>{title} · {artist} · {t(`${n} tracks`, `${n} ${fdPl(n, 'трек', 'трека', 'треков')}`)}</div>
        </div>
        <GlassCircle icon="close" size={60} />
      </div>
      <div style={{ padding: '0 14px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 18, height: 100, borderRadius: 24, padding: '0 20px 0 16px', marginBottom: 6,
          background: HF.glassFill, border: `1px solid ${HF.glassBorder}` }}>
          <div style={{ width: 68, height: 68, borderRadius: 16, display: 'flex', alignItems: 'center', justifyContent: 'center', border: `1.5px dashed ${HF.glassBorder}` }}>
            <Icon name="add" size={36} />
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 24, lineHeight: '30px', fontWeight: 700 }}>{t('New queue', 'Новая очередь')}</div>
            <div className="hf-ellipsis" style={{ fontSize: 17, color: HF.text2 }}>{t(`“${title}” · on ${acc.name}`, `«${title}» · в аккаунте ${acc.name}`)}</div>
          </div>
        </div>
        <AcHeader acc={acc} big />
        {queues.map((q) => <AcRow key={q.id} q={q} big />)}
      </div>
      <div style={{ fontSize: 17, lineHeight: '22px', color: HF.text3, margin: '12px 34px 0' }}>
        {t(`Only ${acc.name}’s queues — the album’s account (D84).`, `Только очереди аккаунта ${acc.name} — аккаунта альбома (D84).`)}
      </div>
    </div>
  );
}

// The car album page (tap on an album tile; a hold still opens the quick menu): breadcrumbs Albums › title in the top
// row, back returns to the grid. Wheel side: cover, title, artist · year · tracks, then the big actions (Play,
// Shuffle, Play next, Add to end, Add to queue…, Start radio — Yandex only). Far side: the tracks in 88 dp rows; a
// tap plays the album from that track (in the album's account's queue, D84). Mirrors for the right-hand wheel.
const CB_ALBUM = {
  yandex: { title: 'Дикие травы', artist: 'Мельница', year: 2009, hue: 'green', min: 52,
    tracks: [...AM_ALBUM_TRACKS, ['Дорога сна', '4:12'], ['Сонг', '3:46'], ['Невеста Полоза', '5:03'], ['Белая кошка', '3:28'], ['Ночная кобыла', '4:20'], ['Прощание', '4:31']] },
  jellyfin: { title: 'Night Drive', artist: 'Lumen Lane', year: 2021, hue: 'violet', min: 44,
    tracks: [['Headlights', '3:58'], ['Night Drive', '4:21'], ['Tunnel Lights', '3:40'], ['Exit 14', '4:02'], ['Sodium Glow', '5:11'], ['Overpass', '3:33'],
      ['Rearview', '4:09'], ['Empty Lanes', '3:47'], ['Dashboard Blue', '4:15'], ['Last Station', '3:52'], ['Home by Dawn', '5:06']] },
};
function CbTrackRow({ n, title, time, playing, pressed }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 22, height: 88, padding: '0 24px', borderRadius: 22,
      background: playing ? 'rgba(255,255,255,0.10)' : 'transparent', boxShadow: pressed ? `0 0 0 3px ${HF.accent}` : 'none',
      borderBottom: playing || pressed ? 'none' : `1px solid ${HF.hairline}` }}>
      <div style={{ width: 40, textAlign: 'center', fontSize: 22, fontWeight: 700, color: HF.text3 }}>
        {playing ? <Icon name="graphic_eq" size={30} color={HF.accent} /> : n}
      </div>
      <div className="hf-ellipsis" style={{ flex: 1, fontSize: 25, fontWeight: playing ? 800 : 600 }}>{title}</div>
      <div style={{ fontSize: 20, color: HF.text2 }}>{time}</div>
    </div>
  );
}
function CbAlbumPage({ side = 'left', provider = 'yandex', pressed = 2 }) {
  const t = useT(); const w = AmUseWords();
  const a = CB_ALBUM[provider];
  const n = a.tracks.length;
  const colW = CB.w - 2 * CB.pad - 40 - CB_ALBUM_LIST_W;
  const at = (left, width) => (side === 'left' ? { left } : { left: CB.w - left - width });
  const big = { fontSize: 21, whiteSpace: 'nowrap', padding: '0 16px' };
  const buttons = [
    ['play_arrow', t('Play', 'Слушать'), true], ['shuffle', w.shuffle], ['skip_next', w.playNext], ['playlist_play', t('Add to end', 'В конец')],
    ['stacks', w.addTo], ...(provider === 'yandex' ? [['sensors', w.radio]] : []),
  ];
  return (
    <CbShell side={side} crumbs={[t('Albums', 'Альбомы'), a.title]} provider={provider} gridLeft={CB.pad + colW + 40}
      tint={provider === 'jellyfin' ? '#3B3355' : '#2E4A3A'} overlay={
      <div style={{ position: 'absolute', top: CB.top, width: colW, ...at(CB.pad, colW) }}>
        <div style={{ display: 'flex', gap: 24, alignItems: 'center' }}>
          <Cover size={200} hue={a.hue} radius={28} />
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 34, lineHeight: '40px', fontWeight: 800 }}>{a.title}</div>
            <div className="hf-ellipsis" style={{ fontSize: 22, color: HF.text, marginTop: 6 }}>{a.artist}</div>
            <div style={{ fontSize: 19, color: HF.text2, marginTop: 4 }}>
              {w.album} · {a.year} · {t(`${n} tracks · ${a.min} min`, `${n} ${fdPl(n, 'трек', 'трека', 'треков')} · ${a.min} мин`)}
            </div>
          </div>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14, marginTop: 26 }}>
          {buttons.map(([icon, label, primary], i) => {
            const style = { ...big, ...(i === buttons.length - 1 && buttons.length % 2 ? { gridColumn: '1 / span 2' } : {}) };
            return primary
              ? <MetalPill key={icon} icon={icon} height={84} style={style}>{label}</MetalPill>
              : <GlassPill key={icon} icon={icon} height={84} style={style}>{label}</GlassPill>;
          })}
        </div>
        {provider === 'jellyfin' && <div style={{ fontSize: 17, color: HF.text3, marginTop: 16 }}>{w.noRadio}</div>}
      </div>}>
      <div style={{ width: '100%' }}>
        {a.tracks.map(([title, time], i) => <CbTrackRow key={title} n={i + 1} title={title} time={time} playing={i === 0} pressed={i === pressed} />)}
      </div>
    </CbShell>
  );
}
// The track list's width (the frame less the wheel-side column, its gap and the margins).
const CB_ALBUM_LIST_W = CB.w - 2 * CB.pad - 620 - 40;
// The page with the tap shown: a ring on the tapped row and a note over it.
function CbAlbumBoard({ side = 'left', provider = 'yandex' }) {
  const t = useT();
  return (
    <div className="hf" style={{ position: 'relative', width: CB.w, height: CB.h }}>
      <CbAlbumPage side={side} provider={provider} />
      <PlNote big style={{ position: 'absolute', top: CB.top + 4 + 88 * 2 + 20, right: side === 'left' ? 140 : CB.w - (CB.pad + CB_ALBUM_LIST_W) + 140 }}>
        {t('Tap a track: the album plays from it', 'Нажатие на трек — альбом играет с него')}
      </PlNote>
    </div>
  );
}

// The entry: the player's ⋮ with "Library" last (below Quality; the menu's own items keep their places).
function CbEntry({ side = 'left' }) {
  const t = useT();
  return (
    <div className="hf" style={{ position: 'relative', width: CB.w, height: CB.h }}>
      <PlCarPlayer w={CB.w} side={side} menu browse />
      <PlNote big icon="library_music" style={{ position: 'absolute', top: 436, ...(side === 'left' ? { left: 470 } : { right: 470 }) }}>
        {t('⋮ → Library: albums, folders, playlists of this queue’s account', '⋮ → Музыка: альбомы, папки, плейлисты аккаунта этой очереди')}
      </PlNote>
    </div>
  );
}

// Boards: the whole car Library joins section 7 (folders-car); see YAMP.html.
function carBrowseBoards() {
  const H = { w: CB.w, h: CB.h };
  return {
    'folders-car': [
      { after: 'cf-home', boards: [
        { id: 'ca-entry-l', label: '(f) Car · the one entry: the player’s ⋮ → Library (last item, marked) · left-hand', ...H, el: <CbEntry /> },
        { id: 'ca-entry-r', label: '(f) Car · ⋮ → Library (last item) · right-hand, mirrored', ...H, el: <CbEntry side="right" /> },
      ] },
      { after: 'cf-menu', boards: [
        { id: 'ca-albums-l', label: '(f) Car Library · Albums tab · a Yandex queue is active → Anna’s albums A–Z · tabs Albums · Playlists · Liked · A–Z rail on the wheel side · left-hand', ...H, el: <CbAlbums /> },
        { id: 'ca-albums-r', label: '(f) Car Library · Albums tab · a Jellyfin queue is active → Home Media’s albums · right-hand, mirrored', ...H, el: <CbAlbums side="right" provider="jellyfin" /> },
        { id: 'ca-menu-l', label: '(f) Hold an album tile → the album menu, car sizes, on the wheel side · Yandex: Play now · Play next · Add to end · Add to queue… · Add to playlist… · Start radio', ...H,
          el: <CbAlbums pressed="Дикие травы" menu /> },
        { id: 'ca-menu-jf-r', label: '(f) Hold a Jellyfin album · right-hand · no Add to playlist / Start radio (Phases 5–6)', ...H,
          el: <CbAlbums side="right" provider="jellyfin" pressed="Night Drive" menu /> },
        { id: 'ca-add-l', label: '(f) Album menu → Add to queue… · car picker in the same panel: Anna’s queues only (the album’s account), "New queue" on top (named after the album), 100 dp rows, back to the menu · left-hand', ...H,
          el: <CbAlbums pressed="Дикие травы" addTo /> },
        { id: 'ca-album-l', label: '(f) Car album page · a tap on an album tile opens it (a long press still opens the quick menu) · breadcrumbs Albums › title, back to the grid · cover, title, artist · year · tracks · Play · Shuffle · Play next · Add to end · Add to queue… · Start radio (Yandex) · big track rows, a tap plays the album from that track · left-hand', ...H,
          el: <CbAlbumBoard /> },
        { id: 'ca-album-r', label: '(f) Car album page · Yandex · right-hand, mirrored (actions and cover on the wheel side, tracks on the far side) · long press on a tile still opens the quick menu', ...H,
          el: <CbAlbumBoard side="right" /> },
        { id: 'ca-album-jf-l', label: '(f) Car album page · Jellyfin album · no Start radio (Phases 5–6) · left-hand · long press on a tile still opens the quick menu', ...H,
          el: <CbAlbumBoard provider="jellyfin" /> },
        { id: 'ca-add-jf-r', label: '(f) Add to queue… on a Jellyfin album · Home Media’s queues only · right-hand, mirrored', ...H,
          el: <CbAlbums side="right" provider="jellyfin" pressed="Night Drive" addTo /> },
      ] },
    ],
  };
}

Object.assign(window, { CB, CbShell, CbTabs, CbAlbums, CbAddToQueue, CbAlbumPage, CbEntry, carBrowseBoards });
