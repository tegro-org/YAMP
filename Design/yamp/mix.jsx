// mix.jsx — 12 · Mix builder (Phase 6). A mix is a recipe: sources from any provider + rules.
// "Build mix" resolves the pool, shuffles it by the rules and saves the result as a persistent MIX queue.
// A saved mix has at most one queue: Play continues it (or builds it the first time), Re-run rebuilds the mix
// and replaces that queue (D29). Saved mixes: Library → Mixes (phone), Mixes tile on car home (head unit).
// The builder lives in the normal interface (phone, landscape) — car mode shows saved mixes only (D32).
// Exposes mixSections(): the canvas sections of this file, each
// { id, title, subtitle, boards: [{ id, label, w, h, el }] } — wired into YAMP.html's buildSections().

const MxKindIcon = { artist: 'person', album: 'album', playlist: 'playlist_play', genre: 'sell', folder: 'folder', fav: 'favorite', queue: 'stacks' };

function MxUseWords() {
  const t = useT();
  return {
    mixes: t('Mixes', 'Миксы'), newMix: t('New mix', 'Новый микс'), build: t('Build mix', 'Собрать микс'), save: t('Save', 'Сохранить'),
    sources: t('Sources', 'Источники'), rules: t('Rules', 'Правила'), addSource: t('Add source', 'Добавить источник'), add: t('Add', 'Добавить'),
    liked: t('Liked', 'Мне нравится'), rerun: t('Re-run', 'Пересобрать'), play: t('Play', 'Слушать'), edit: t('Edit', 'Изменить'), del: t('Delete', 'Удалить'), cancel: t('Cancel', 'Отмена'),
    builderHint: t('Built once — then it is a queue', 'Собирается один раз и становится очередью'),
    estimate: t('About 1 240 tracks match · 200 go into the mix', 'Подходит около 1 240 треков · в микс войдёт 200'),
    noEstimate: t('Add a source to see how many tracks match', 'Добавьте источник — покажем, сколько треков подходит'),
    recent: t('Skip recently played', 'Не повторять недавнее'), days: t('14 days', '14 дней'),
    inRow: t('Same artist in a row', 'Исполнитель подряд'), inRowCap: t('no more than 2 tracks', 'не больше 2 треков'),
    favs: t('Liked tracks more often', 'Любимое чаще'), years: t('Years', 'Годы'), anyYear: t('any', 'любые'),
    noBooks: t('No audiobooks or podcasts', 'Без аудиокниг и подкастов'), length: t('Tracks', 'Треков'),
  };
}

// The sources of the example mix: every kind, both providers (so pills carry provider badges).
function MxUseSources() {
  const t = useT();
  return [
    { kind: 'artist', name: 'Pink Floyd', provider: 'jellyfin' }, { kind: 'artist', name: 'Queen', provider: 'jellyfin' },
    { kind: 'artist', name: 'Сплин', provider: 'yandex' }, { kind: 'folder', name: 'RC - Sorted', provider: 'jellyfin' },
    { kind: 'fav', name: t('Liked', 'Мне нравится'), provider: 'yandex' }, { kind: 'genre', name: 'Rock', provider: 'jellyfin' },
    { kind: 'queue', name: t('Solo drive', 'Один за рулём') },
  ];
}

// Saved mix definitions. `queue` = the mix's queue exists (never more than one per mix; Play continues it).
// `sources` = parts of the one-line summary; { fav } = the "Liked" list of that provider (D30: never merged).
function MxUseMixes() {
  const t = useT();
  return [
    { id: 'road', name: t('Road trip', 'В дорогу'), hues: ['amber', 'rose', 'blue', 'teal'], sources: ['Pink Floyd', 'Queen', 'Сплин + 4'],
      meta: t('Built 2 days ago · 37 of 200', 'Собран 2 дня назад · 37 из 200'), queue: '37 / 200' },
    { id: 'family', name: t('Family evening', 'Семейный вечер'), hues: ['rose', 'blue', 'green', 'amber'], sources: [{ fav: 'yandex' }, 'Детские', 'The Beatles'],
      meta: t('Built yesterday · 112 of 200', 'Собран вчера · 112 из 200'), queue: '112 / 200' },
    { id: 'rock', name: t('Russian rock', 'Русский рок'), hues: ['blue', 'amber', 'green', 'gray'], sources: ['Сплин', 'Чайф', 'Аквариум и БГ (mp3) + 2'],
      meta: t('Built 3 weeks ago', 'Собран 3 недели назад') },
    { id: 'calm', name: t('Quiet evening', 'Тихий вечер'), hues: ['violet', 'green', 'teal', 'violet'], sources: ['Fleur', 'Мельница & Хелависа - альбомы'],
      meta: t('Built in October', 'Собран в октябре') },
    { id: '90s', name: t('The nineties', 'Девяностые'), hues: ['teal', 'gray', 'rose', 'blue'], sources: [{ fav: 'jellyfin' }], tail: '1990–1999',
      meta: t('Not built yet', 'Ещё не собирался') },
  ];
}

// ── Local primitives ─────────────────────────────────────────────────────────
// Mix cover: 2×2 mosaic of its sources' covers.
function MxMosaic({ hues, w = 56, h, radius = 12, badge }) {
  const hh = h || w;
  return (
    <div style={{ position: 'relative', width: w, height: hh, flex: '0 0 auto' }}>
      <div style={{ width: w, height: hh, borderRadius: radius, overflow: 'hidden', display: 'flex', flexWrap: 'wrap', border: `1px solid ${HF.hairline}` }}>
        {hues.map((hue, i) => <Cover key={i} size={(w - 2) / 2} hue={hue} radius={0} style={{ height: (hh - 2) / 2, border: 'none' }} />)}
      </div>
      {badge && (
        <div style={{ position: 'absolute', right: -6, bottom: -6, width: 22, height: 22, borderRadius: 11, background: HF.sheet,
          border: `1px solid ${HF.glassBorder}`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name="auto_awesome" size={14} color={HF.text2} />
        </div>
      )}
    </div>
  );
}

// A mix's sources on one line (inside an ellipsized text block). "Liked" carries its provider's badge.
function MxSrcLine({ m, badge = 14 }) {
  const w = MxUseWords();
  return (
    <React.Fragment>
      {m.sources.map((s, i) => (
        <React.Fragment key={i}>
          {i > 0 && ', '}
          {s.fav ? <React.Fragment>{w.liked}<ProviderBadge provider={s.fav} size={badge} style={{ display: 'inline-block', verticalAlign: 'middle', margin: `-2px 0 0 ${Math.round(badge * 0.3)}px` }} /></React.Fragment> : s}
        </React.Fragment>
      ))}
      {m.tail && ` · ${m.tail}`}
    </React.Fragment>
  );
}

// Source pill: kind glyph, name, provider badge (the list mixes providers), remove.
function MxSourcePill({ s, big, small, removable = true }) {
  const h = big ? 56 : small ? 36 : 40;
  return (
    <div style={{ height: h, borderRadius: h / 2, padding: big ? (removable ? '0 14px 0 18px' : '0 20px 0 18px') : (removable ? '0 8px 0 12px' : '0 14px 0 12px'),
      display: 'inline-flex', alignItems: 'center', gap: big ? 10 : 6, flex: '0 0 auto', maxWidth: '100%', minWidth: 0,
      background: HF.glassFill, border: `1px solid ${HF.glassBorder}`, fontSize: big ? 19 : 14, fontWeight: 700 }}>
      <Icon name={MxKindIcon[s.kind]} size={big ? 24 : 18} color={HF.text2} />
      <span className="hf-ellipsis">{s.name}</span>
      {s.provider && <ProviderBadge provider={s.provider} size={big ? 20 : 15} />}
      {removable && <Icon name="close" size={big ? 24 : 18} color={HF.text2} style={{ marginLeft: big ? 4 : 2 }} />}
    </div>
  );
}

function MxStepper({ value, big }) {
  const h = big ? 56 : 40;
  const side = (icon) => <div style={{ width: h, height: h, display: 'flex', alignItems: 'center', justifyContent: 'center' }}><Icon name={icon} size={big ? 26 : 20} /></div>;
  return (
    <div style={{ height: h, borderRadius: h / 2, display: 'flex', alignItems: 'center', flex: '0 0 auto', background: HF.glassFill, border: `1px solid ${HF.glassBorder}` }}>
      {side('remove')}<div style={{ minWidth: big ? 26 : 18, textAlign: 'center', fontSize: big ? 22 : 17, fontWeight: 800 }}>{value}</div>{side('add')}
    </div>
  );
}

// HiFiSlider with two thumbs (year range).
function MxRange({ from = 0.25, to = 0.65, style }) {
  const thumb = (v) => (
    <div style={{ position: 'absolute', left: `calc(${v * 100}% - 10px)`, top: 2, width: 20, height: 20, borderRadius: 10,
      background: `radial-gradient(circle at 50% 30%, ${HF.steelLight}, ${HF.steelDark})`, boxShadow: '0 0 0 1.5px rgba(201,206,216,0.7), 0 2px 6px rgba(0,0,0,0.6)' }} />
  );
  return (
    <div style={{ position: 'relative', height: 24, ...style }}>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 10, height: 4, borderRadius: 2, background: 'rgba(255,255,255,0.12)' }} />
      <div style={{ position: 'absolute', left: `${from * 100}%`, width: `${(to - from) * 100}%`, top: 10, height: 4, borderRadius: 2, background: '#fff' }} />
      {thumb(from)}{thumb(to)}
    </div>
  );
}

function MxCheck({ on, size = 28 }) {
  return (
    <div style={{ width: size, height: size, borderRadius: size / 2, flex: '0 0 auto', display: 'flex', alignItems: 'center', justifyContent: 'center',
      background: on ? HF.accent : 'transparent', border: `1.5px solid ${on ? 'rgba(255,255,255,0.25)' : HF.glassBorder}` }}>
      {on && <Icon name="check" size={Math.round(size * 0.68)} weight={700} />}
    </div>
  );
}

// Rule row, phone: plaque + title (+ caption) + control; the slider sits on a second line with its value.
function MxRule({ icon, title, caption, value, control, below, last }) {
  return (
    <div style={{ padding: '0 14px', borderBottom: last ? 'none' : `1px solid ${HF.hairline}` }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, height: caption ? 56 : 48 }}>
        <IconPlaque icon={icon} size={32} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <Txt t="rowTitle" ellipsis>{title}</Txt>
          {caption && <Txt t="caption" color={HF.text2} ellipsis>{caption}</Txt>}
        </div>
        {value && <Txt t="body" color={HF.text3}>{value}</Txt>}
        {control}
      </div>
      {below && <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '0 0 10px 44px' }}>{below}</div>}
    </div>
  );
}
// Rule row, head unit: one 68 dp line — title, slider, value, control.
function MxRuleBig({ icon, title, caption, value, slider, control, last }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 16, height: 68, padding: '0 18px', borderBottom: last ? 'none' : `1px solid ${HF.hairline}` }}>
      <IconPlaque icon={icon} size={44} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="hf-ellipsis" style={{ fontSize: 21, lineHeight: '26px', fontWeight: 600 }}>{title}</div>
        {caption && <div className="hf-ellipsis" style={{ fontSize: 15, lineHeight: '19px', color: HF.text2 }}>{caption}</div>}
      </div>
      {slider && <div style={{ width: 220, flex: '0 0 auto' }}><div style={{ zoom: 1.3 }}>{slider}</div></div>}
      {value && <div style={{ minWidth: 96, textAlign: 'right', fontSize: 19, fontWeight: 700, whiteSpace: 'nowrap' }}>{value}</div>}
      {control || (slider && <div style={{ width: 68, flex: '0 0 auto' }} />)}
    </div>
  );
}

// The rules card. `filled` = the example mix; otherwise the defaults.
function MxRules({ filled, big }) {
  const w = MxUseWords();
  const fav = filled ? '×2' : '×1', favV = filled ? 0.25 : 0;
  const card = { borderRadius: big ? 24 : 18, background: HF.glassFill, border: `1px solid ${HF.hairline}` };
  const lengths = ['50', '100', '200', '500'];
  if (big) {
    const sw = (on) => <div style={{ zoom: 1.3, flex: '0 0 auto' }}><Switch on={on} /></div>;
    return (
      <div style={card}>
        <MxRuleBig icon="history" title={w.recent} slider={<Slider value={0.23} />} value={w.days} control={sw(true)} />
        <MxRuleBig icon="group" title={w.inRow} caption={w.inRowCap} control={<MxStepper value="2" big />} />
        <MxRuleBig icon="favorite" title={w.favs} slider={<Slider value={favV} />} value={fav} />
        <MxRuleBig icon="calendar_month" title={w.years} slider={filled && <MxRange from={0.26} to={0.64} />} value={filled ? '1970–1999' : w.anyYear} control={sw(filled)} />
        <MxRuleBig icon="menu_book" title={w.noBooks} control={sw(true)} />
        <MxRuleBig icon="format_list_numbered" title={w.length} last control={<div style={{ zoom: 1.3, flex: '0 0 auto' }}><Segmented options={lengths} value="200" style={{ width: 250 }} /></div>} />
      </div>
    );
  }
  const val = (s) => <div style={{ minWidth: 58, textAlign: 'right', fontSize: 15, fontWeight: 700, whiteSpace: 'nowrap' }}>{s}</div>;
  return (
    <div style={card}>
      <MxRule icon="history" title={w.recent} control={<Switch on />} below={<React.Fragment><Slider value={0.23} style={{ flex: 1 }} />{val(w.days)}</React.Fragment>} />
      <MxRule icon="group" title={w.inRow} caption={w.inRowCap} control={<MxStepper value="2" />} />
      <MxRule icon="favorite" title={w.favs} below={<React.Fragment><Slider value={favV} style={{ flex: 1 }} />{val(fav)}</React.Fragment>} />
      <MxRule icon="calendar_month" title={w.years} value={filled ? null : w.anyYear} control={<Switch on={filled} />}
        below={filled && <React.Fragment><MxRange from={0.26} to={0.64} style={{ flex: 1 }} />{val('1970–1999')}</React.Fragment>} />
      <MxRule icon="menu_book" title={w.noBooks} control={<Switch on />} />
      <MxRule icon="format_list_numbered" title={w.length} last control={<Segmented options={lengths} value="200" style={{ width: 214, height: 36 }} />} />
    </div>
  );
}

function MxTop({ title, trailing }) {
  return (
    <div style={{ position: 'absolute', left: 16, right: 16, top: 20, display: 'flex', alignItems: 'center', gap: 12 }}>
      <GlassCircle icon="arrow_back" />
      <div style={{ flex: 1, minWidth: 0 }}>{title && <LargeTitle>{title}</LargeTitle>}</div>
      {trailing}
    </div>
  );
}

// ── Phone: entry point and saved mixes ───────────────────────────────────────
function MxLibrary() {
  const t = useT(); const w = MxUseWords();
  return (
    <Frame kind="phone">
      <div style={{ position: 'absolute', left: 20, right: 20, top: 22, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <LargeTitle>{t('My music', 'Моя музыка')}</LargeTitle><GlassCircle icon="settings" />
      </div>
      <div style={{ position: 'absolute', left: 20, right: 20, top: 82 }}>
        <div style={{ display: 'flex', gap: 12 }}>
          <MetalPill icon="play_arrow" style={{ flex: 1 }}>{w.play}</MetalPill>
          <GlassPill icon="shuffle" style={{ flex: 1 }}>{t('Shuffle', 'Перемешать')}</GlassPill>
        </div>
        <div style={{ marginTop: 14 }}>
          <NavRow icon="favorite" title={w.liked} count={312} />
          <NavRow icon="playlist_play" title={t('Playlists', 'Плейлисты')} count={9} />
          <NavRow icon="auto_awesome" title={w.mixes} count={5} />
          <NavRow icon="folder" title={t('Folders', 'Папки')} count={34} last />
        </div>
        <SectionCaps style={{ margin: '18px 0 12px' }}>{t('Recently added', 'Недавно добавленные')}</SectionCaps>
        <div style={{ display: 'flex', gap: 12 }}>
          {[['The Wall', 'Pink Floyd', 'rose'], ['Дикие травы', 'Мельница', 'green']].map(([a, b, hue]) => (
            <div key={a} style={{ width: 180, minWidth: 0 }}>
              <Cover size={180} hue={hue} radius={18} style={{ height: 150 }} />
              <Txt t="cardTitle" ellipsis style={{ marginTop: 8 }}>{a}</Txt><Txt t="caption" color={HF.text2} ellipsis>{b}</Txt>
            </div>
          ))}
        </div>
      </div>
      <PhoneChrome active="library" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />
    </Frame>
  );
}

// Row tap = open the mix. Play = continue its queue (or build it the first time); ↻ = rebuild, replacing that queue.
function MxMixRow({ m, last }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 14, height: 80 }}>
      <MxMosaic hues={m.hues} w={56} badge />
      <div style={{ flex: 1, minWidth: 0, alignSelf: 'stretch', display: 'flex', alignItems: 'center', gap: 8, borderBottom: last ? 'none' : `1px solid ${HF.hairline}` }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <Txt t="rowTitle" ellipsis>{m.name}</Txt>
          <Txt t="caption" color={HF.text2} ellipsis><MxSrcLine m={m} /></Txt>
          <Txt t="caption" color={HF.text3} ellipsis>{m.meta}</Txt>
        </div>
        {m.queue && <GlassCircle icon="autorenew" size={44} />}
        <GlassCircle icon="play_arrow" size={44} />
      </div>
    </div>
  );
}

function MxMixesBody({ playing }) {
  const t = useT(); const w = MxUseWords(); const mixes = MxUseMixes();
  return (
    <React.Fragment>
      <MxTop title={w.mixes} />
      <div style={{ position: 'absolute', left: 16, right: 16, top: 82 }}>
        <GlassPill icon="add">{w.newMix}</GlassPill>
        <SectionCaps style={{ margin: '20px 4px 6px' }}>{t(`Saved · ${mixes.length}`, `Сохранённые · ${mixes.length}`)}</SectionCaps>
        <div style={{ padding: '0 4px' }}>
          {mixes.map((m, i) => <MxMixRow key={m.id} m={m} last={i === mixes.length - 1} />)}
        </div>
        <Txt t="caption" color={HF.text3} style={{ margin: '12px 4px 0' }}>
          {t('▶ continues the queue of the mix, or builds it. ↻ rebuilds the mix and replaces that queue.', '▶ продолжает очередь микса или собирает её. ↻ собирает микс заново и заменяет эту очередь.')}
        </Txt>
      </div>
      {playing || <PhoneChrome active="library" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />}
    </React.Fragment>
  );
}
function MxMixes() { return <Frame kind="phone"><MxMixesBody /></Frame>; }

// ── Phone: the builder (full-height sheet, like Settings) ────────────────────
function MxBuilder({ filled }) {
  const t = useT(); const w = MxUseWords(); const sources = MxUseSources();
  return (
    <Sheet height={891}>
      <QueueSheetHeader title={w.newMix} subtitle={w.builderHint} />
      <div style={{ padding: '0 16px' }}>
        <SectionCaps style={{ margin: '4px 4px 10px' }}>{filled ? `${w.sources} · ${sources.length}` : w.sources}</SectionCaps>
        {filled ? (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {sources.map((s) => <MxSourcePill key={s.name} s={s} />)}
            <GlassPill icon="add" height={40} active>{w.add}</GlassPill>
          </div>
        ) : (
          <div style={{ borderRadius: 18, background: HF.glassFill, border: `1px solid ${HF.hairline}`, padding: '18px 20px', display: 'flex', flexDirection: 'column', alignItems: 'center', textAlign: 'center' }}>
            <Icon name="library_add" size={30} color={HF.text3} />
            <Txt t="cardTitle" style={{ marginTop: 8 }}>{t('No sources yet', 'Источников пока нет')}</Txt>
            <Txt t="caption" color={HF.text2} style={{ margin: '4px 0 12px' }}>
              {t('Artists, albums, playlists, genres, folders, liked tracks or a queue — from any provider.', 'Исполнители, альбомы, плейлисты, жанры, папки, любимое или очередь — из любого провайдера.')}
            </Txt>
            <GlassPill icon="add" height={40} active>{w.addSource}</GlassPill>
          </div>
        )}
        <SectionCaps style={{ margin: '16px 4px 8px' }}>{w.rules}</SectionCaps>
        <MxRules filled={filled} />
      </div>
      <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, padding: '12px 16px 20px', background: HF.sheet, borderTop: `1px solid ${HF.hairline}` }}>
        <Txt t="caption" color={HF.text2} ellipsis style={{ textAlign: 'center', marginBottom: 10 }}>{filled ? w.estimate : w.noEstimate}</Txt>
        <div style={{ display: 'flex', gap: 10, opacity: filled ? 1 : 0.4 }}>
          <GlassPill icon="bookmark">{w.save}</GlassPill>
          <MetalPill icon="auto_awesome" style={{ flex: 1 }}>{w.build}</MetalPill>
        </div>
      </div>
    </Sheet>
  );
}
function MxBuilderBoard({ filled, children }) {
  return (
    <Frame kind="phone">
      <MxMixesBody /><Scrim />
      <MxBuilder filled={filled} />
      {children}
    </Frame>
  );
}

function MxSaveDialog() {
  const t = useT(); const w = MxUseWords();
  return (
    <MxBuilderBoard filled>
      <Scrim opacity={0.55} />
      <Dialog title={t('Save mix', 'Сохранить микс')} width={340}
        actions={<React.Fragment><DialogAction>{w.cancel}</DialogAction><DialogAction kind="primary">{w.save}</DialogAction></React.Fragment>}>
        <Field value={t('Road trip', 'В дорогу')} style={{ marginTop: 16 }} />
        <Txt t="caption" color={HF.text3} style={{ marginTop: 10 }}>
          {t('Sources and rules are saved, not the tracks. Saved mixes are in Library → Mixes and on the car screen.', 'Сохраняются источники и правила, а не треки. Сохранённые миксы — в «Музыка → Миксы» и на экране авто.')}
        </Txt>
      </Dialog>
    </MxBuilderBoard>
  );
}

// ── Phone: add source ────────────────────────────────────────────────────────
function MxPickRow({ cover, title, caption, provider, on, chevron, last }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, height: 64 }}>
      {cover}
      <div style={{ flex: 1, minWidth: 0, alignSelf: 'stretch', display: 'flex', alignItems: 'center', gap: 10, borderBottom: last ? 'none' : `1px solid ${HF.hairline}` }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <Txt t="rowTitle" ellipsis>{title}</Txt>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 1 }}>
            {provider && <ProviderBadge provider={provider} size={15} />}
            <Txt t="caption" color={HF.text2} ellipsis>{caption}</Txt>
          </div>
        </div>
        <MxCheck on={on} />
        {chevron && <Icon name="chevron_right" size={22} color={HF.text3} />}
      </div>
    </div>
  );
}

function MxAddSource({ type = 'artists' }) {
  const t = useT(); const w = MxUseWords(); const fw = useFolderWords();
  const types = [['artists', t('Artists', 'Исполнители')], ['albums', t('Albums', 'Альбомы')], ['playlists', t('Playlists', 'Плейлисты')], ['genres', t('Genres', 'Жанры')],
    ['folders', t('Folders', 'Папки')], ['fav', t('Liked', 'Любимое')], ['queues', t('Queues', 'Очереди')]];
  const nAlb = (n) => t(`${n} albums`, `${n} альбомов`);
  const artists = [['Louna', 'yandex', nAlb(9), 'amber'], ['Metallica', 'jellyfin', nAlb(12), 'gray'], ['Pink Floyd', 'jellyfin', nAlb(15), 'amber', true],
    ['Pink Floyd', 'yandex', nAlb(28), 'violet'], ['Queen', 'jellyfin', nAlb(18), 'rose', true], ['Мельница', 'jellyfin', nAlb(13), 'green'], ['Сплин', 'yandex', nAlb(16), 'blue', true]];
  const folders = ROOT_FOLDERS.slice(3, 10);
  return (
    <MxBuilderBoard filled>
      <Scrim opacity={0.55} />
      <Sheet height={800}>
        <QueueSheetHeader title={w.addSource} subtitle={t('Any provider · tick several', 'Любой провайдер · можно несколько')} />
        <div style={{ padding: '0 16px', display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {types.map(([id, label]) => <FilterPill key={id} active={id === type}>{label}</FilterPill>)}
        </div>
        <Field icon="search" placeholder={type === 'folders' ? fw.filter : t('Find an artist', 'Найти исполнителя')} style={{ margin: '12px 16px 4px' }} />
        <div style={{ padding: '0 20px' }}>
          {type === 'folders'
            ? folders.map(([name, hue, n], i) => (
              <MxPickRow key={name} cover={<Cover size={48} hue={hue} glyph="folder" />} title={name} caption={fw.nFolders(n)} on={name === 'RC - Sorted'} chevron last={i === folders.length - 1} />))
            : artists.map(([name, provider, cap, hue, on], i) => (
              <MxPickRow key={name + provider} cover={<Cover size={48} hue={hue} radius={24} />} title={name} caption={cap} provider={provider} on={on} last={i === artists.length - 1} />))}
        </div>
        <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, padding: '12px 16px 20px', background: HF.sheet, borderTop: `1px solid ${HF.hairline}` }}>
          <MetalPill icon="add">{type === 'folders' ? t('Add 1 folder', 'Добавить 1 папку') : t('Add 3 artists', 'Добавить 3 исполнителей')}</MetalPill>
        </div>
      </Sheet>
    </MxBuilderBoard>
  );
}

// ── Phone: building and the result ───────────────────────────────────────────
function MxBuilding() {
  const t = useT(); const w = MxUseWords();
  return (
    <MxBuilderBoard filled>
      <Scrim opacity={0.55} />
      <Dialog title={t('Building the mix…', 'Собираем микс…')} width={340} actions={<DialogAction>{w.cancel}</DialogAction>}>
        <ProgressBar value={0.7} height={4} style={{ marginTop: 18 }} />
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 12 }}>
          <Txt t="body" color={HF.text2} ellipsis>{t('Source 5 of 7 · RC - Sorted', 'Источник 5 из 7 · RC - Sorted')}</Txt><ProviderBadge provider="jellyfin" size={15} />
        </div>
        <Txt t="caption" color={HF.text3} style={{ marginTop: 4 }}>{t('1 020 tracks found so far', 'Пока найдено 1 020 треков')}</Txt>
      </Dialog>
    </MxBuilderBoard>
  );
}

// The builder closes, the mix's queue is active and plays; the Queues sheet opens once to show where it went.
// First build: the queue is new and on top. Re-run: the same queue is updated in place (D29) — no second one.
function MxResult() {
  const t = useT();
  const mix = { id: 'mix', kind: 'mix', name: t('Road trip', 'В дорогу'), track: 'Under Pressure', artist: 'Queen', hue: 'rose', pos: 1, total: 200, progress: 0.06, active: true };
  const queues = [mix, ...useQueues().map((q) => ({ ...q, active: false }))];
  return (
    <Frame kind="phone" tint="#7A2E4A">
      <MxMixesBody playing /><PhoneChrome active="library" title="Under Pressure" artist="Queen" hue="rose" /><Scrim />
      <Sheet height={730}>
        <QueueSheetHeader title={t('Queues', 'Очереди')} subtitle={t('Mix built · 200 tracks · playing', 'Микс собран · 200 треков · играет')} />
        <div style={{ padding: '0 8px', display: 'flex', flexDirection: 'column', gap: 4 }}>
          {queues.map((q) => <QueueRow key={q.id} q={q} />)}
        </div>
        <div style={{ position: 'absolute', left: 20, right: 20, bottom: 24 }}><GlassPill icon="add">{t('New queue', 'Новая очередь')}</GlassPill></div>
      </Sheet>
    </Frame>
  );
}

// ── Phone: a saved mix ───────────────────────────────────────────────────────
// `playing` = the mix's queue is the one playing now (then Re-run asks first, see MxRerunConfirm).
function MxDetail({ playing, children }) {
  const t = useT(); const w = MxUseWords(); const sources = MxUseSources();
  const rules = [['history', t('Nothing played in the last 14 days', 'Без игравшего за последние 14 дней')],
    ['group', t('Same artist: no more than 2 in a row', 'Один исполнитель — не больше 2 подряд')],
    ['favorite', t('Liked tracks twice as often', 'Любимое — вдвое чаще')], ['calendar_month', t('Years 1970–1999', 'Годы 1970–1999')],
    ['menu_book', w.noBooks], ['format_list_numbered', t('200 tracks', '200 треков')]];
  return (
    <Frame kind="phone" tint="#8A5A22">
      <MxTop />
      <div style={{ position: 'absolute', left: 16, right: 16, top: 80 }}>
        <div style={{ display: 'flex', gap: 14, alignItems: 'center', marginBottom: 16 }}>
          <MxMosaic hues={['amber', 'rose', 'blue', 'teal']} w={96} radius={16} />
          <div style={{ minWidth: 0 }}>
            <div className="hf-ellipsis" style={{ fontSize: 26, lineHeight: '31px', fontWeight: 800 }}>{t('Road trip', 'В дорогу')}</div>
            <Txt t="caption" color={HF.text2} style={{ marginTop: 4 }}>{t('Mix · 7 sources · about 1 240 tracks', 'Микс · 7 источников · около 1 240 треков')}</Txt>
            <Txt t="caption" color={HF.text2}>{playing ? t('Playing now · 37 of 200', 'Сейчас играет · 37 из 200') : t('Built 2 days ago · queue at 37 of 200', 'Собран 2 дня назад · очередь: 37 из 200')}</Txt>
          </div>
        </div>
        <div style={{ display: 'flex', gap: 10 }}>
          <MetalPill icon="play_arrow" style={{ flex: 1 }}>{w.play}</MetalPill>
          <GlassPill icon="autorenew" style={{ flex: 1, padding: '0 12px' }}>{w.rerun}</GlassPill>
        </div>
        <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
          <GlassPill icon="edit" height={40} style={{ flex: 1 }}>{w.edit}</GlassPill>
          <GlassPill height={40} style={{ flex: 1, color: HF.danger }}><Icon name="delete" size={18} color={HF.danger} />{w.del}</GlassPill>
        </div>
        <SectionCaps style={{ margin: '18px 4px 8px' }}>{`${w.sources} · ${sources.length}`}</SectionCaps>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {sources.map((s) => <MxSourcePill key={s.name} s={s} small removable={false} />)}
        </div>
        <SectionCaps style={{ margin: '16px 4px 4px' }}>{w.rules}</SectionCaps>
        <div style={{ padding: '0 4px' }}>
          {rules.map(([icon, text]) => (
            <div key={icon} style={{ display: 'flex', alignItems: 'center', gap: 12, height: 32 }}>
              <Icon name={icon} size={20} color={HF.text2} /><Txt t="body" ellipsis>{text}</Txt>
            </div>
          ))}
        </div>
      </div>
      {playing
        ? <PhoneChrome active="library" title="Under Pressure" artist="Queen" hue="rose" />
        : <PhoneChrome active="library" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />}
      {children}
    </Frame>
  );
}

// Re-run replaces the mix's queue, so the place in it is lost. Asked only when that queue is the one playing;
// otherwise Re-run starts at once.
function MxRerunConfirm() {
  const t = useT(); const w = MxUseWords();
  return (
    <MxDetail playing>
      <Scrim />
      <Dialog title={t('Re-run the mix?', 'Пересобрать микс?')} width={340}
        text={t('“Road trip” is playing now. Its queue will be replaced and the mix starts again from the first track.', '«В дорогу» сейчас играет. Очередь микса будет заменена, и он начнётся заново с первого трека.')}
        actions={<React.Fragment><DialogAction>{w.cancel}</DialogAction><DialogAction kind="primary">{w.rerun}</DialogAction></React.Fragment>} />
    </MxDetail>
  );
}

// ── Head unit ────────────────────────────────────────────────────────────────
// Tile (and its knob) = play the mix: continue its queue, or build it the first time. ↻ = rebuild, replacing that queue.
function MxCarTile({ m, w = 344, coverH, font = 26, knob = 84 }) {
  const chip = { position: 'absolute', background: HF.barFill, border: `1px solid ${HF.glassBorder}`, display: 'flex', alignItems: 'center', justifyContent: 'center' };
  return (
    <div style={{ width: w, flex: '0 0 auto' }}>
      <div style={{ position: 'relative' }}>
        <MxMosaic hues={m.hues} w={w} h={coverH || w} radius={28} />
        {m.queue && (
          <div style={{ ...chip, left: 12, top: 12, height: 44, borderRadius: 22, padding: '0 16px 0 12px', gap: 8, fontSize: 19, fontWeight: 700 }}>
            <Icon name="stacks" size={22} color={HF.text2} />{m.queue}
          </div>
        )}
        {m.queue && <div style={{ ...chip, right: 12, top: 12, width: 64, height: 64, borderRadius: 32 }}><Icon name="autorenew" size={32} /></div>}
        <Knob icon="play_arrow" size={knob} style={{ position: 'absolute', right: 12, bottom: 12 }} />
      </div>
      <div className="hf-ellipsis" style={{ fontSize: font, lineHeight: `${font + 6}px`, fontWeight: 700, marginTop: 10 }}>{m.name}</div>
      <div className="hf-ellipsis" style={{ fontSize: font - 8, lineHeight: `${font - 2}px`, color: HF.text2 }}><MxSrcLine m={m} badge={18} /></div>
    </div>
  );
}

// "Mixes" is one of the shared car tiles (D33, queues.jsx) — highlighted here.
function MxCarHome() {
  return (
    <Frame kind="head" tint="#8A5A22" glowY="40%">
      <CarHome activeTile="mixes" />
    </Frame>
  );
}

function MxCarMixes() {
  const t = useT(); const w = MxUseWords(); const mixes = MxUseMixes();
  return (
    <Frame kind="head" tint="#22335F" glowY="20%">
      <div style={{ position: 'absolute', left: 48, right: 48, top: 28, display: 'flex', alignItems: 'center', gap: 16 }}>
        <GlassCircle icon="arrow_back" size={60} />
        <div style={{ fontSize: 40, fontWeight: 800 }}>{w.mixes}</div>
        <div style={{ flex: 1 }} />
        <NowPlayingCapsule />
      </div>
      <div style={{ position: 'absolute', left: 48, top: 122, display: 'flex', gap: 26 }}>
        {mixes.map((m) => <MxCarTile key={m.id} m={m} />)}
      </div>
      <div style={{ position: 'absolute', left: 48, right: 48, bottom: 30, display: 'flex', alignItems: 'center', gap: 12, fontSize: 19, color: HF.text2 }}>
        <Icon name="info" size={24} color={HF.text3} fill={0} />
        <div className="hf-ellipsis">{t('To make or change a mix: Library → Mixes', 'Собрать или изменить микс: Музыка → Миксы')}</div>
      </div>
    </Frame>
  );
}

// The normal landscape UI (Library → Mixes), where typing lives (D32). The builder is a centred two-column panel.
function MxHeadBuilder() {
  const t = useT(); const w = MxUseWords(); const sources = MxUseSources(); const mixes = MxUseMixes();
  return (
    <Frame kind="head" tint="#22335F" glowY="20%">
      <div style={{ position: 'absolute', left: 48, top: 30, fontSize: 40, fontWeight: 800 }}>{w.mixes}</div>
      <div style={{ position: 'absolute', left: 48, top: 110, display: 'flex', gap: 26 }}>
        {mixes.map((m) => <MxCarTile key={m.id} m={m} />)}
      </div>
      <Dock active="library" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />
      <Scrim />
      <SidePanel width={1500} side="left" style={{ left: 210 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, padding: '20px 28px 0' }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 36, lineHeight: '42px', fontWeight: 800 }}>{w.newMix}</div>
            <div className="hf-ellipsis" style={{ fontSize: 17, color: HF.text2, marginTop: 2 }}>{w.builderHint}</div>
          </div>
          <GlassPill icon="bookmark" height={60} style={{ fontSize: 19, padding: '0 24px' }}>{w.save}</GlassPill>
          <GlassCircle icon="close" size={60} />
        </div>
        {/* sources */}
        <div style={{ position: 'absolute', left: 28, top: 112, width: 600 }}>
          <div style={{ ...HFType.sectionCaps, fontSize: 15, color: HF.text2, margin: '0 4px 12px' }}>{`${w.sources} · ${sources.length}`}</div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10 }}>
            {sources.map((s) => <MxSourcePill key={s.name} s={s} big />)}
            <GlassPill icon="add" height={56} active style={{ fontSize: 19, padding: '0 22px' }}>{w.add}</GlassPill>
          </div>
        </div>
        <div style={{ position: 'absolute', left: 645, top: 112, bottom: 112, width: 1, background: HF.hairline }} />
        {/* rules */}
        <div style={{ position: 'absolute', left: 662, right: 28, top: 112 }}>
          <div style={{ ...HFType.sectionCaps, fontSize: 15, color: HF.text2, margin: '0 4px 12px' }}>{w.rules}</div>
          <MxRules filled big />
        </div>
        <div style={{ position: 'absolute', left: 28, right: 28, bottom: 0, height: 96, borderTop: `1px solid ${HF.hairline}`, display: 'flex', alignItems: 'center', gap: 20 }}>
          <div className="hf-ellipsis" style={{ flex: 1, fontSize: 20, color: HF.text2 }}>{w.estimate}</div>
          <MetalPill icon="auto_awesome" height={64} style={{ fontSize: 22, width: 380 }}>{w.build}</MetalPill>
        </div>
      </SidePanel>
    </Frame>
  );
}

// Slim split pane: saved mixes as a list (one column), same actions as the tiles.
function MxSlimMixes() {
  const t = useT(); const w = MxUseWords(); const mixes = MxUseMixes();
  return (
    <Frame kind="splitN" tint="#22335F" glowY="20%">
      <div style={{ position: 'absolute', left: 28, right: 28, top: 24, display: 'flex', alignItems: 'center', gap: 14 }}>
        <GlassCircle icon="arrow_back" size={60} />
        <div style={{ fontSize: 36, fontWeight: 800, flex: 1 }}>{w.mixes}</div>
      </div>
      <div style={{ position: 'absolute', left: 28, right: 28, top: 100 }}>
        {mixes.map((m, i) => (
          <div key={m.id} style={{ display: 'flex', alignItems: 'center', gap: 16, height: 100, borderBottom: i === mixes.length - 1 ? 'none' : `1px solid ${HF.hairline}` }}>
            <MxMosaic hues={m.hues} w={68} radius={16} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="hf-ellipsis" style={{ fontSize: 23, lineHeight: '29px', fontWeight: 700 }}>{m.name}</div>
              <div className="hf-ellipsis" style={{ fontSize: 16, lineHeight: '21px', color: HF.text2 }}><MxSrcLine m={m} badge={16} /></div>
              {m.queue && <div style={{ fontSize: 16, lineHeight: '21px', color: HF.text3 }}>{t('Queue', 'Очередь')} · {m.queue}</div>}
            </div>
            {m.queue && <GlassCircle icon="autorenew" size={60} iconSize={30} />}
            <Knob icon="play_arrow" size={64} />
          </div>
        ))}
      </div>
      <div style={{ position: 'absolute', left: 28, bottom: 20 }}><NowPlayingCapsule width={584} /></div>
    </Frame>
  );
}

function mixSections() {
  const P = FRAMES.phone, H = FRAMES.head, SN = FRAMES.splitN;
  const sz = (f) => ({ w: f.w, h: f.h });
  return [
    { id: 'mix-phone', title: '12a · Mix builder — phone (Phase 6)',
      subtitle: 'A mix is a recipe: sources from any provider + rules. “Build mix” resolves the pool, shuffles it by the rules and saves the result as a persistent MIX queue that starts playing. A saved mix has at most one queue: ▶ continues it (or builds it the first time); Re-run (↻) rebuilds the mix and replaces that queue, so the place in it is lost — it asks first only when that queue is the one playing (D29). A “Liked” source is one provider’s list and carries its badge (D30). Saved mixes live in Library → Mixes. DESIGN_GUIDE §8, plan Phase 6.',
      boards: [
        { id: 'mx-library', label: 'Library · “Mixes” row (Liked, Playlists, Folders, Mixes)', ...sz(P), el: <MxLibrary /> },
        { id: 'mx-list', label: 'Mixes · saved mixes (row = open, ▶ play, ↻ re-run = replace its queue)', ...sz(P), el: <MxMixes /> },
        { id: 'mx-builder-empty', label: 'Builder · empty, rules at defaults', ...sz(P), el: <MxBuilderBoard /> },
        { id: 'mx-add-artists', label: 'Add source · type + multi-select (providers mixed)', ...sz(P), el: <MxAddSource type="artists" /> },
        { id: 'mx-add-folders', label: 'Add source · folders (tick = whole folder, › = go inside)', ...sz(P), el: <MxAddSource type="folders" /> },
        { id: 'mx-builder', label: 'Builder · sources, rules, pool estimate', ...sz(P), el: <MxBuilderBoard filled /> },
        { id: 'mx-save', label: 'Save · name the mix', ...sz(P), el: <MxSaveDialog /> },
        { id: 'mx-building', label: 'Build mix · resolving the sources', ...sz(P), el: <MxBuilding /> },
        { id: 'mx-result', label: 'Result · Queues with the mix’s queue: new and on top after the first build; a re-run updates it in place', ...sz(P), el: <MxResult /> },
        { id: 'mx-detail', label: 'A saved mix · Play / Re-run / Edit / Delete', ...sz(P), el: <MxDetail /> },
        { id: 'mx-rerun-confirm', label: 'Re-run · asked only when this mix’s queue is the one playing', ...sz(P), el: <MxRerunConfirm /> },
      ] },
    { id: 'mix-car', title: '12b · Mix builder — head unit 1920×720 (Phase 6)',
      subtitle: 'Car mode shows saved mixes only and never needs typing: car home → Mixes → tile = 2 taps. A tile plays the mix in 1 tap (continues its queue, or builds it the first time); ↻ rebuilds the mix and replaces that queue (D29). The builder lives in the normal landscape interface (Library → Mixes): a centred two-column panel with 56 dp controls (D32). “Mixes” is one of the shared car tiles (D33).',
      boards: [
        { id: 'mx-car-home', label: 'Car home · “Mixes” in the shared tile row (highlighted)', ...sz(H), el: <MxCarHome /> },
        { id: 'mx-car-mixes', label: 'Car mode · saved mixes as tiles (1 tap = play, ↻ = rebuild)', ...sz(H), el: <MxCarMixes /> },
        { id: 'mx-head-builder', label: 'Normal landscape interface · builder panel (sources left, rules right)', ...sz(H), el: <MxHeadBuilder /> },
        { id: 'mx-slim-mixes', label: 'Car mode, slim pane 640×720 · saved mixes as a list', ...sz(SN), el: <MxSlimMixes /> },
      ] },
  ];
}

Object.assign(window, { mixSections });
