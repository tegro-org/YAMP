// player.jsx — Main screen + new player screen (tasks/todo.md, 2026-11-17).
// Components only (no sections of its own): `playerBoards()` places each variant next to an existing board —
// the main screen beside q-home (section 4), the phone player beside the Phase 3b player boards (section 4), the car
// layout beside car mode (section 5), Settings → Player beside Settings → Playback (section 9).
//
// Two screens:
//   Main screen — as built, two changes: the queue dropdown at the top; a tap around Play or on the title opens the
//                 player (the "Player" link goes). The mini player on the other tabs is unchanged;
//                 the "In car" tab becomes "Player" (the car screen is dropped, its ideas move to the Car layout).
//   Player      — layout from Settings (Phone / Car), no automatic choice. Large current cover, the next tracks
//                 smaller (1 or 3, a setting), progress with times. Drag up = queue; drag down closes the open queue
//                 first, then the player. Car: bigger controls, the ring around Play, a header row with the queue dropdown
//                 and "Tune the wave". The player never has a buttons bar (the car tiles are gone from it).
//   Likes       — (i) buttons as today, or (ii) gestures: double tap on the cover = like, hold = dislike.

const PL_NEXT = [['Shine On You Crazy Diamond', 'Pink Floyd', 'amber'], ['Дороги', 'Мельница', 'green'], ['Comfortably Numb', 'Pink Floyd', 'violet']];

// Frame of any size (phone landscape 960×540 dp drawn at 2×, car 1760×720).
function PlFrame({ w, h, tint = '#8A5A22', glowY = '30%', children }) {
  return (
    <div className="hf" style={{ position: 'relative', width: w, height: h, overflow: 'hidden', background: HF.ground }}>
      <Backdrop tint={tint} glowY={glowY}>{children}</Backdrop>
    </div>
  );
}

// The queue dropdown: the active queue's name + ▾ (opens the switcher).
function PlDropdown({ big, name, lead, style }) {
  const t = useT();
  const h = big ? 60 : 44;
  return (
    <div style={{ height: h, borderRadius: h / 2, padding: big ? '0 22px' : '0 14px', display: 'inline-flex', alignItems: 'center', gap: big ? 12 : 8,
      background: `linear-gradient(180deg, ${HF.anodLight}, ${HF.anodDark})`, boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.20), 0 0 0 1.5px rgba(201,206,216,0.50)',
      fontSize: big ? 22 : 16, fontWeight: 700, ...style }}>
      <span style={{ flexShrink: 0, display: 'inline-flex' }}>{lead || <Icon name="stacks" size={big ? 28 : 20} />}</span>
      <span className="hf-ellipsis" style={{ maxWidth: big ? 300 : 180, minWidth: 0, flexShrink: 1 }}>{name || t('With family', 'С семьёй')}</span>
      <span style={{ flexShrink: 0, display: 'inline-flex' }}><Icon name="expand_more" size={big ? 28 : 20} color={HF.text2} /></span>
    </div>
  );
}

// A caption chip on the board (explains a gesture or a tap target).
function PlNote({ icon = 'touch_app', children, big, style }) {
  return (
    <div style={{ display: 'inline-flex', alignItems: 'center', gap: big ? 12 : 8, height: big ? 48 : 34, padding: big ? '0 20px' : '0 14px',
      borderRadius: big ? 24 : 17, background: 'rgba(15,17,24,0.92)', border: `1px solid ${HF.glassBorder}`, fontSize: big ? 20 : 13, fontWeight: 600, ...style }}>
      <Icon name={icon} size={big ? 26 : 18} color={HF.accent} />{children}
    </div>
  );
}

// A dashed outline marking a tap target.
function PlTarget({ left, top, width, height, radius = 24 }) {
  return <div style={{ position: 'absolute', left, top, width, height, borderRadius: radius, border: `2px dashed ${HF.accent}`, boxShadow: '0 0 0 4px rgba(0,0,0,0.25)' }} />;
}

// Progress with times under it.
function PlProgress({ played = 0.34, buffered = 0.6, big, width }) {
  const time = (f) => { const s = Math.round(f * 312); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };
  return (
    <div style={{ width }}>
      {big ? <BufferSlider played={played} buffered={buffered} /> : <BufferBar played={played} buffered={buffered} height={5} />}
      <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: big ? 6 : 8, fontSize: big ? 22 : 13, fontWeight: big ? 600 : 400, color: HF.text2 }}>
        <span>{time(played)}</span><span>5:12</span>
      </div>
    </div>
  );
}

// Next tracks: one row (tap → queue) or three cards (current large elsewhere, these smaller).
function PlNextOne({ big, style }) {
  const t = useT(); const [a, b, hue] = PL_NEXT[0];
  const c = big ? 84 : 52;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: big ? 18 : 12, height: big ? 108 : 68, borderRadius: big ? 26 : 18, padding: big ? '0 22px 0 14px' : '0 12px 0 8px',
      background: HF.glassFill, border: `1px solid ${HF.glassBorder}`, ...style }}>
      <Cover size={c} hue={hue} radius={big ? 18 : 12} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ ...HFType.sectionCaps, fontSize: big ? 16 : 11, color: HF.text2 }}>{t('Next', 'Далее')}</div>
        <div className="hf-ellipsis" style={{ fontSize: big ? 26 : 16, fontWeight: 700 }}>{a}</div>
        <div className="hf-ellipsis" style={{ fontSize: big ? 20 : 13, color: HF.text2 }}>{b}</div>
      </div>
      <Icon name="queue_music" size={big ? 34 : 22} color={HF.text2} />
    </div>
  );
}
function PlNextCards({ size = 96, big, style }) {
  const t = useT();
  return (
    <div style={style}>
      <div style={{ ...HFType.sectionCaps, fontSize: big ? 16 : 11, color: HF.text2, marginBottom: big ? 10 : 6 }}>{t('Next', 'Далее')}</div>
      <div style={{ display: 'flex', gap: big ? 18 : 12 }}>
        {PL_NEXT.map(([a, b, hue]) => (
          <div key={a} style={{ width: size }}>
            <Cover size={size} hue={hue} radius={big ? 20 : 14} />
            <div className="hf-ellipsis" style={{ fontSize: big ? 20 : 13, fontWeight: 700, marginTop: big ? 8 : 6 }}>{a}</div>
            <div className="hf-ellipsis" style={{ fontSize: big ? 17 : 11, color: HF.text2 }}>{b}</div>
          </div>
        ))}
      </div>
    </div>
  );
}
// Car: the next three as a column of rows (first larger than the rest is not needed — the current cover is the large one).
function PlNextColumn({ style }) {
  const t = useT();
  return (
    <div style={style}>
      <div style={{ ...HFType.sectionCaps, fontSize: 17, color: HF.text2, marginBottom: 10 }}>{t('Next', 'Далее')}</div>
      {PL_NEXT.map(([a, b, hue]) => (
        <div key={a} style={{ display: 'flex', alignItems: 'center', gap: 18, height: 104 }}>
          <Cover size={84} hue={hue} radius={18} />
          <div style={{ minWidth: 0 }}>
            <div className="hf-ellipsis" style={{ fontSize: 26, fontWeight: 700 }}>{a}</div>
            <div className="hf-ellipsis" style={{ fontSize: 20, color: HF.text2 }}>{b}</div>
          </div>
        </div>
      ))}
    </div>
  );
}

// Like feedback for the gesture variant: a heart burst on the cover / a dislike confirmation.
function PlHeartBurst({ size }) {
  return (
    <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', pointerEvents: 'none' }}>
      <div style={{ position: 'absolute', width: size * 0.8, height: size * 0.8, borderRadius: '50%', background: 'radial-gradient(circle, rgba(255,90,120,0.45) 0%, transparent 70%)' }} />
      <Icon name="favorite" size={Math.round(size * 0.42)} color={HF.love} />
    </div>
  );
}
function PlToast({ icon, text, action, big, style }) {
  return (
    <div style={{ display: 'inline-flex', alignItems: 'center', gap: big ? 16 : 10, height: big ? 64 : 46, padding: big ? '0 26px' : '0 16px', borderRadius: big ? 32 : 23,
      background: 'rgba(28,30,38,0.96)', border: `1px solid ${HF.glassBorder}`, fontSize: big ? 22 : 15, fontWeight: 700, ...style }}>
      <Icon name={icon} size={big ? 30 : 22} />{text}
      {action && <span style={{ color: HF.accent, marginLeft: big ? 12 : 6 }}>{action}</span>}
    </div>
  );
}

// The tab bar with the "In car" tab replaced by "Player" (the car screen is dropped; its ideas live in the player's
// Car layout). Drawn here so the existing boards keep today's bar.
function PlTabBar({ active = 'home', mark }) {
  const t = useT();
  const tabs = [
    { id: 'home', icon: 'waves', label: t('Wave', 'Волна') },
    { id: 'search', icon: 'search', label: t('Search', 'Поиск') },
    { id: 'library', icon: 'library_music', label: t('Library', 'Музыка') },
    { id: 'radio', icon: 'radio', label: t('Radio', 'Радио') },
    { id: 'player', icon: 'play_circle', label: t('Player', 'Плеер') },
  ];
  return (
    <div style={{ position: 'absolute', left: 16, right: 16, bottom: 16 }}>
      <div style={{ height: 66, borderRadius: HFShape.bar + 1, background: HF.barFill, border: `1px solid ${HF.glassBorder}`,
        display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 6px' }}>
        {tabs.map((tb) => (
          <div key={tb.id} style={{ position: 'relative', width: 70, height: 54, borderRadius: 27, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 2,
            background: tb.id === active ? 'rgba(255,255,255,0.14)' : 'transparent',
            outline: mark && tb.id === 'player' ? `2px dashed ${HF.accent}` : 'none', outlineOffset: 2 }}>
            <Icon name={tb.icon} size={22} color={tb.id === active ? HF.accent : '#fff'} />
            <div style={{ fontSize: 11, fontWeight: 600, color: tb.id === active ? '#fff' : HF.text2 }}>{tb.label}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ── 1. Main screen ───────────────────────────────────────────────────────────
// As built (My Wave), with the queue dropdown at the top and without the "Player" link; the two tap targets that
// open the player are outlined.
function PlMainScreen() {
  return (
    <Frame kind="phone" tint="#2C4FA8" glowY="26%">
      <PlMainContent notes />
      <PlTabBar mark />
    </Frame>
  );
}
// The main screen's content without the tab bar; `notes` = the tap targets and caption chips of pl-main.
// Reused by homejf.jsx (Home with Jellyfin sections below the hero).
function PlMainContent({ notes }) {
  const t = useT();
  return (
    <React.Fragment>
      <div style={{ position: 'absolute', left: 20, top: 22 }}><LargeTitle>{t('My Wave', 'Моя волна')}</LargeTitle></div>
      <div style={{ position: 'absolute', right: 20, top: 18, display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 8 }}>
        <PlDropdown />
        <GlassPill icon="tune" height={36}>{t('Tune the wave', 'Настроить волну')}</GlassPill>
      </div>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 176, display: 'flex', justifyContent: 'center' }}><SpectrumRing size={290} knob={164} /></div>
      {notes && <PlTarget left={56} top={168} width={300} height={306} radius={150} />}
      <div style={{ position: 'absolute', left: 20, right: 20, top: 488, display: 'flex', alignItems: 'center', gap: 12 }}>
        <GlassCircle icon="heart_broken" size={48} />
        <div style={{ flex: 1, textAlign: 'center', minWidth: 0 }}>
          <div className="hf-ellipsis" style={{ fontSize: 24, fontWeight: 800 }}>{t('First song', 'Первая песня')}</div>
          <Txt t="body" color={HF.text2}>{t('The Band', 'Группа')}</Txt>
        </div>
        <GlassCircle icon="favorite" size={48} color={HF.love} />
      </div>
      {notes && <PlTarget left={82} top={482} width={248} height={62} radius={18} />}
      <SectionCaps style={{ position: 'absolute', left: 20, right: 12, top: 566 }}>{t('Next in the wave', 'Дальше в волне')}</SectionCaps>
      <div style={{ position: 'absolute', left: 20, top: 604, display: 'flex', gap: 12 }}>
        {[['A very long song title', 'Artist with a long name', 'rose'], [t('Third song', 'Третья песня'), t('Artist', 'Исполнитель'), 'green']].map(([a, b, hue]) => (
          <div key={a} style={{ width: 120 }}>
            <Cover size={120} hue={hue} radius={18} />
            <Txt t="cardTitle" ellipsis style={{ marginTop: 6 }}>{a}</Txt><Txt t="caption" color={HF.text2} ellipsis>{b}</Txt>
          </div>
        ))}
      </div>
      {notes && <PlNote style={{ position: 'absolute', left: 20, top: 78 }}>{t('Tap = player', 'Нажатие = плеер')}</PlNote>}
      {notes && <PlNote icon="arrow_downward" style={{ position: 'absolute', right: 20, bottom: 96 }}>{t('"In car" tab → "Player"', 'Вкладка «В авто» → «Плеер»')}</PlNote>}
    </React.Fragment>
  );
}

// ── 2. Player — Phone layout, portrait ───────────────────────────────────────
// As built + the next tracks: `next` 1 (one row, tap → queue) or 3 (cards). `likes` 'buttons' | 'gestures'.
// `drag` = a mid-drag frame of the swipe up (the queue rising).
// The ⋮ menu — the only control on the top row besides the queue dropdown (2026-11-18). Order: (1) "Tune the wave"
// on My Wave / "Start radio" on other Yandex queues / nothing on Jellyfin, (2) Search, (3) Sleep timer, (4) Add to
// playlist…, (5) Quality · <format>, (6) Like / Dislike only with like gestures on. `big` = the car's sizes.
// `browse` (carbrowse.jsx, (f)): "Library" last (user 2026-11-19) — the car's one entry to albums, folders and playlists.
function PlMoreMenu({ gestures, slot = 'wave', big, browse, style }) {
  const t = useT();
  const items = [
    ...(slot === 'wave' ? [['tune', t('Tune the wave', 'Настроить волну')]] : []),
    ...(slot === 'radio' ? [['sensors', t('Start radio', 'Запустить радио')]] : []),
    ['search', t('Search', 'Поиск')],
    ['bedtime', t('Sleep timer', 'Таймер сна')],
    ['playlist_add', t('Add to playlist…', 'Добавить в плейлист…')],
    ['graphic_eq', t('Quality · MP3 320 kbps', 'Качество · MP3 320 кбит/с')],
    ...(gestures ? [['favorite', t('Like', 'Нравится')], ['heart_broken', t('Dislike', 'Не нравится')]] : []),
    ...(browse ? [['library_music', t('Library', 'Музыка')]] : []),
  ];
  return (
    <div style={{ position: 'absolute', width: big ? 400 : 270, borderRadius: big ? 24 : 18, background: HF.sheet, border: `1px solid ${HF.hairline}`,
      boxShadow: '0 12px 40px rgba(0,0,0,0.55)', padding: big ? '8px 0' : '6px 0', zIndex: 5, ...style }}>
      {items.map(([icon, text]) => (
        <div key={icon} style={{ display: 'flex', alignItems: 'center', gap: big ? 18 : 14, height: big ? 64 : 48, padding: big ? '0 22px' : '0 16px',
          ...(icon === 'library_music' ? { background: 'rgba(10,108,255,0.16)', boxShadow: `inset 3px 0 0 ${HF.accent}`, borderTop: `1px solid ${HF.hairline}` } : {}) }}>
          <Icon name={icon} size={big ? 28 : 22} color={icon === 'favorite' ? HF.love : icon === 'library_music' ? '#fff' : HF.text2} />
          <div className="hf-ellipsis" style={{ fontSize: big ? 20 : 15, fontWeight: 600 }}>{text}</div>
        </div>
      ))}
    </div>
  );
}

// The top row of every player layout: ⋮ first, then the queue dropdown taking the rest (long names truncate).
function PlTopRow({ big, menuOpen, queueName, queueLead, extra, reverse, style }) {
  const sz = big ? 60 : 44;
  return (
    <div style={{ position: 'absolute', display: 'flex', alignItems: 'center', gap: big ? 14 : 8, flexDirection: reverse ? 'row-reverse' : 'row', ...style }}>
      <div style={{ flexShrink: 0, borderRadius: sz / 2, boxShadow: menuOpen ? `0 0 0 2px ${HF.accent}` : 'none' }}><GlassCircle icon="more_vert" size={sz} /></div>
      <PlDropdown big={big} name={queueName} lead={queueLead} style={{ flex: '0 1 auto', minWidth: 0, overflow: 'hidden' }} />
      {extra}
    </div>
  );
}

function PlPhonePlayer({ next = 1, likes = 'buttons', burst, toast, drag, menu, slot = 'wave', queueName }) {
  // Matches the wide player (2026-11-18): the top row is ⋮ then the queue dropdown (PlTopRow, always top-left in
  // portrait); everything else — Tune the wave / Start radio, search, sleep timer, add to playlist, quality, likes
  // with gestures — lives in ⋮ (PlMoreMenu). No minimise, queue, search or sleep-timer buttons. The title block is centred — queue details, title, artist — with dislike left and like right. Cover,
  // progress with times, Prev · Play · Next and the next track(s) stay where today's player has them; "3" replaces
  // the next card with three compact cards. Swipe down closes the player, swipe up opens the queue.
  const t = useT();
  // "3" needs room for the square cards at the bottom, so the cover is smaller and everything sits higher.
  const cover = next === 3 ? 272 : 352, coverTop = 74;
  const labelsTop = coverTop + cover + 24, sliderTop = labelsTop + 112, transportTop = sliderTop + 62;
  return (
    <Frame kind="phone" tint="#2C4FA8" glowY="22%">
      <div style={{ position: 'absolute', inset: 0, transform: drag ? 'translateY(-170px)' : 'none' }}>
        <PlTopRow menuOpen={menu} queueName={queueName} style={{ left: 16, right: 16, top: 10 }} />
        <div style={{ position: 'absolute', left: (412 - cover) / 2, top: coverTop, width: cover, height: cover }}>
          <Cover size={cover} hue="blue" radius={26} style={{ boxShadow: '0 0 60px rgba(60,90,200,0.25)' }} />
          {burst && <PlHeartBurst size={cover} />}
        </div>
        <div style={{ position: 'absolute', left: 16, right: 16, top: labelsTop, display: 'flex', alignItems: 'center', gap: 10 }}>
          {likes === 'buttons' ? <GlassCircle icon="heart_broken" size={48} /> : <div style={{ width: 48 }} />}
          <div style={{ flex: 1, minWidth: 0, textAlign: 'center' }}>
            <div style={{ ...HFType.sectionCaps, fontSize: 12, color: HF.text2 }}>{t('Queue · 12 of 48', 'Очередь · 12 из 48')}</div>
            <div className="hf-ellipsis" style={{ fontSize: 23, lineHeight: '28px', fontWeight: 800 }}>{t('First song', 'Первая песня')}</div>
            <div className="hf-ellipsis" style={{ fontSize: 16, color: HF.text2 }}>{t('The Band', 'Группа')}</div>
          </div>
          {likes === 'buttons' ? <GlassCircle icon="favorite" size={48} color={HF.love} /> : <div style={{ width: 48 }} />}
        </div>
        <div style={{ position: 'absolute', left: 21, right: 21, top: sliderTop }}>
          <BufferSlider played={0.38} buffered={0.62} />
          <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 4, fontSize: 13, color: HF.text2 }}><span>1:22</span><span>−2:13</span></div>
        </div>
        <div style={{ position: 'absolute', left: 21, right: 21, top: transportTop, height: 110, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 34 }}>
          <Knob icon="skip_previous" size={72} />
          <Knob icon="pause" size={104} />
          <NextKnob state="ready" size={72} />
        </div>
        {menu && <PlMoreMenu gestures={likes === 'gestures'} slot={slot} style={{ left: 16, top: 60 }} />}
        {next === 3 ? <PlNextStrip /> : <PlNextCard />}
      </div>
      {drag && (
        <div style={{ position: 'absolute', left: 0, right: 0, top: 915 - 190, bottom: 0, borderRadius: '28px 28px 0 0', background: HF.sheet, border: `1px solid ${HF.hairline}` }}>
          <div style={{ width: 44, height: 5, borderRadius: 3, background: HF.text3, margin: '10px auto 8px' }} />
          <div style={{ padding: '0 20px' }}>
            <SectionCaps style={{ margin: '4px 0 6px' }}>{t('Next', 'Далее')}</SectionCaps>
            <TrackRow title="Shine On You Crazy Diamond" artist="Pink Floyd" hue="amber" />
          </div>
        </div>
      )}
      {drag && <PlDragZones />}
      {toast && <div style={{ position: 'absolute', left: 0, right: 0, top: coverTop + cover - 70, display: 'flex', justifyContent: 'center' }}>{toast}</div>}
    </Frame>
  );
}

// Today's single next-track card (bottom of the player): tap → the queue; also a swipe-up zone.
function PlNextCard() {
  const t = useT(); const [a, , hue] = PL_NEXT[0];
  return (
    <div style={{ position: 'absolute', left: 21, right: 21, top: 837, height: 63, borderRadius: 18, background: HF.glassFill, border: `1px solid ${HF.glassBorder}`,
      display: 'flex', alignItems: 'center', gap: 12, padding: '0 14px 0 10px' }}>
      <Cover size={43} hue={hue} radius={10} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ ...HFType.sectionCaps, fontSize: 10, color: HF.text2 }}>{t('Next', 'Далее')}</div>
        <div className="hf-ellipsis" style={{ fontSize: 15, fontWeight: 700 }}>{a}</div>
      </div>
      <Icon name="chevron_right" size={22} color={HF.text2} />
    </div>
  );
}
// "3": three compact cards in the same place as today's card.
// "Next tracks 3" in portrait: the same cards as the landscape row — square covers in a row, title and artist under
// each (one line each, within the card's width). Three across the phone width, at the bottom.
function PlNextStrip({ w = 412 }) {
  const gap = 10, side = 16, card = Math.floor((w - 2 * side - 2 * gap) / 3);
  return (
    <div style={{ position: 'absolute', left: side, bottom: 18, display: 'flex', gap }}>
      {PL_NEXT.map(([a, b, hue]) => (
        <div key={a} style={{ width: card, minWidth: 0 }}>
          <Cover size={card} hue={hue} radius={16} />
          <div className="hf-ellipsis" style={{ width: card, fontSize: 13, fontWeight: 700, marginTop: 6 }}>{a}</div>
          <div className="hf-ellipsis" style={{ width: card, fontSize: 12, color: HF.text2 }}>{b}</div>
        </div>
      ))}
    </div>
  );
}
// Where a swipe up starts: the bottom edge and the next-track card(s) (drawn before the drag offset).
function PlDragZones() {
  const t = useT();
  return (
    <React.Fragment>
      {/* the card(s), shown where they are mid-drag (moved up with the player) */}
      <div style={{ position: 'absolute', left: 14, right: 14, top: 660, height: 77, borderRadius: 20, border: `2px dashed ${HF.accent}`, background: 'rgba(80,140,255,0.10)' }} />
      <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height: 12, background: 'rgba(80,140,255,0.55)' }} />
      <PlNote icon="swipe_up" style={{ position: 'absolute', left: 0, right: 0, margin: '0 auto', width: 'fit-content', bottom: 22 }}>
        {t('Swipe up from the bottom edge or the next track(s)', 'Вверх от нижнего края или от следующих треков')}</PlNote>
    </React.Fragment>
  );
}

// The queue opened by the swipe up; a swipe down closes it (back to the player, not out of it).
function PlPhoneQueueOpen() {
  const t = useT();
  return (
    <Frame kind="phone" tint="#8A5A22" glowY="18%">
      <div style={{ position: 'absolute', left: 16, right: 16, top: 18, display: 'flex', alignItems: 'center', gap: 12 }}>
        <Cover size={56} hue="amber" radius={12} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="hf-ellipsis" style={{ fontSize: 17, fontWeight: 700 }}>Wish You Were Here</div>
          <div style={{ fontSize: 13, color: HF.text2 }}>Pink Floyd</div>
        </div>
        <Knob icon="pause" size={48} />
      </div>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 92, bottom: 0, borderRadius: '28px 28px 0 0', background: HF.sheet, border: `1px solid ${HF.hairline}` }}>
        <div style={{ width: 44, height: 5, borderRadius: 3, background: HF.text3, margin: '10px auto 8px' }} />
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '4px 16px 8px 20px' }}>
          <PlDropdown />
          <div style={{ display: 'flex', gap: 14 }}><Icon name="shuffle" size={22} color={HF.text2} /><Icon name="repeat" size={22} color={HF.text2} /></div>
        </div>
        <div style={{ padding: '0 20px' }}>
          <SectionCaps style={{ margin: '6px 0 8px' }}>{t('Now playing', 'Сейчас играет')}</SectionCaps>
          <div style={{ borderRadius: 18, padding: '0 12px', margin: '0 -8px', background: `linear-gradient(180deg, ${HF.anodLight}, ${HF.anodDark})`,
            boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.20), 0 0 0 1.5px rgba(201,206,216,0.50)' }}>
            <TrackRow title="Wish You Were Here" artist="Pink Floyd" hue="amber" trailing={<Icon name="graphic_eq" size={24} />} />
          </div>
          <SectionCaps style={{ margin: '18px 0 4px' }}>{t('Next', 'Далее')}</SectionCaps>
          {PL_NEXT.concat([['Time', 'Pink Floyd', 'teal'], ['Ветер', 'Мельница', 'rose']]).map(([a, b, hue]) => (
            <TrackRow key={a} title={a} artist={b} hue={hue} trailing={<Icon name="drag_handle" size={22} color={HF.text3} />} />
          ))}
        </div>
      </div>
      <PlNote icon="swipe_down" style={{ position: 'absolute', left: 0, right: 0, margin: '0 auto', width: 'fit-content', bottom: 22 }}>
        {t('Swipe down — closes the queue first, then the player', 'Вниз — сначала закрывает очередь, потом плеер')}</PlNote>
    </Frame>
  );
}

// ── 3. Player — Phone layout, landscape ──────────────────────────────────────
// ── Wide-player geometry (car + phone landscape) — one rule, meant to be reused as-is by the app ─────────────
// `W`×`H` = the screen (dp), `big` = the car sizes (Large controls), `next` = 1 | 3.
//  1. The cover + next block spans the full height: its top and bottom padding = the ⋮ · queue row's top padding.
//     Cover (square) = the height left after the next block; the next block = the cover's width.
//  2. Far edge: a minimum padding = the progress bar's edge padding (not an alignment).
//  3. Taller screens: the cover keeps the full height, the controls side gets narrower.
//  4. Wider screens: the controls keep today's share of the width as a maximum; the cover block is centred in the
//     space to the right of the controls (the minimum far padding still holds when that space is tight).
function plWideLayout({ W, H, next = 3, big }) {
  const pad = big ? 26 : 16;                       // the ⋮ · queue row's top padding (= block top and bottom)
  const edge = big ? 48 : 24;                      // the progress bar's edge padding (= minimum far padding)
  const share = big ? 820 / 1760 : 540 / 960;      // the controls' share of the width today = its maximum
  const gapMin = big ? 50 : 30, minControls = big ? 560 : 380;
  const g = big ? 16 : 12, cg = big ? 14 : 10, text = big ? 50 : 36;     // 3 cards: gap, card gap, title+artist
  const oneG = big ? 18 : 14, oneH = big ? 84 : 63;                       // 1 card: gap, card height
  const avail = H - 2 * pad;
  let cover = next === 3 ? Math.floor(((avail - g - text) * 3 + 2 * cg) / 4) : avail - oneG - oneH;
  cover = Math.max(120, Math.min(cover, W - 2 * edge - gapMin - minControls));   // only extreme aspects hit this
  const card = Math.floor((cover - 2 * cg) / 3);
  // Taller screens (rule 3): no spare width — the controls take all that is left and the cover block ends exactly at
  // the minimum right padding. Wide screens (rule 4): the controls stop at their cap and the cover block is centred in
  // the space beside them. A spare of less than two minimum gaps is not worth centring and goes to the controls too.
  // Taller = width/height below 1.66 (user, 2026-11-19; the app's TALLER_RATIO): 16:10 and 4:3 are taller, 16:9 is wide.
  const cap = Math.round(share * W), fit = W - 2 * edge - gapMin - cover;
  const centred = W / H >= 1.66 && fit - cap >= 2 * gapMin;
  const controlsW = centred ? cap : fit;
  const areaLeft = edge + controlsW, areaRight = W - edge;
  const coverLeft = centred ? Math.round((areaLeft + areaRight - cover) / 2) : areaRight - cover;
  // The clock (car only) has one fixed position on every screen, never moved per case: right offset = the minimum
  // right padding + an inset, top offset = the cover's top padding + the same inset. Chosen from the edge case where
  // the cover block reaches the right padding (taller screens): there the clock lies fully inside the cover's top-right
  // corner; on wider screens it sits at the same offset, overlapping the cover or not.
  const clockW = big ? 200 : 0, clockH = big ? 72 : 0, inset = 20;
  const clockX = W - edge - inset - clockW, clockY = pad + inset;
  const overlaps = clockX < coverLeft + cover && clockX + clockW > coverLeft && clockY < pad + cover;
  return {
    pad, edge, controlsW, cover, card, cg, text, oneH,
    coverLeft, coverTop: pad,
    clock: { x: clockX, y: clockY, w: clockW, h: clockH, onCover: big && overlaps },
    nextTop: pad + cover + (next === 3 ? g : oneG),
  };
}

function PlPhoneLandscape({ next = 1, slot = 'wave', menu, side }) {
  // The approved car arrangement adapted to phone landscape: top row queue · tune · search; the labels centred
  // between dislike / like; progress with times; Prev · Play · Next. The far side: the cover centred, the next
  // track(s) under it (1 = one card, 3 = three small square cards). Laid out at 960×540 dp and drawn at 2× on a
  // 1920×1080 board (FRAMES.land) — a 1080p phone in landscape.
  const W = 960, H = 540, S = FRAMES.land.w / W;
  return (
    <div style={{ width: FRAMES.land.w, height: FRAMES.land.h, overflow: 'hidden' }}>
      <div style={{ width: W, height: H, transform: `scale(${S})`, transformOrigin: '0 0' }}>
        <PlPhoneLandscapeBody next={next} W={W} H={H} slot={slot} menu={menu} side={side} />
      </div>
    </div>
  );
}

function PlPhoneLandscapeBody({ next, W, H, slot = 'wave', menu, side = 'left' }) {
  const t = useT();
  // Mirrors with the wheel side like the car layout: left = controls left, cover right; right = the reverse.
  const X = (left, width) => (side === 'left' ? left : W - left - width);
  // Geometry from the shared rule (plWideLayout): the cover block spans the full height at the far edge.
  const G = plWideLayout({ W, H, next, big: false });
  const leftW = G.controlsW, cover = G.cover, card = G.card, farTop = G.coverTop;
  const coverX = G.coverLeft;
  return (
    <PlFrame w={W} h={H} tint="#2C4FA8" glowY="30%">
      <PlTopRow menuOpen={menu} reverse={side !== 'left'} style={{ left: X(24, leftW), top: 16, width: leftW, justifyContent: 'flex-start' }} />
      {menu && <PlMoreMenu slot={slot} style={{ top: 68, ...(side === 'left' ? { left: 24 } : { right: 24 }) }} />}
      <div style={{ position: 'absolute', left: X(24, leftW), top: 150, width: leftW }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <GlassCircle icon="heart_broken" size={48} />
          <div style={{ flex: 1, minWidth: 0, textAlign: 'center' }}>
            <div style={{ ...HFType.sectionCaps, fontSize: 12, color: HF.text2 }}>{t('Queue · 12 of 48', 'Очередь · 12 из 48')}</div>
            <div className="hf-ellipsis" style={{ fontSize: 28, lineHeight: '34px', fontWeight: 800 }}>{t('First song', 'Первая песня')}</div>
            <div className="hf-ellipsis" style={{ fontSize: 17, color: HF.text2 }}>{t('The Band', 'Группа')}</div>
          </div>
          <GlassCircle icon="favorite" size={48} color={HF.love} />
        </div>
        <div style={{ marginTop: 22 }}><PlProgress width={leftW} /></div>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 44, marginTop: 24 }}>
          <Knob icon="skip_previous" size={74} />
          <Knob icon="pause" size={108} />
          <NextKnob state="ready" size={74} />
        </div>
      </div>
      <div style={{ position: 'absolute', left: X(coverX, cover), top: farTop }}><Cover size={cover} hue="blue" radius={24} /></div>
      {next === 3
        ? <div style={{ position: 'absolute', left: X(coverX, cover), top: G.nextTop, width: cover, display: 'flex', gap: G.cg }}>
            {PL_NEXT.map(([a, b, hue]) => (
              <div key={a} style={{ width: card, minWidth: 0 }}>
                <Cover size={card} hue={hue} radius={14} />
                {/* title and artist, each one line within the card's width */}
                <div className="hf-ellipsis" style={{ width: card, fontSize: 12, fontWeight: 700, marginTop: 4 }}>{a}</div>
                <div className="hf-ellipsis" style={{ width: card, fontSize: 11, color: HF.text2 }}>{b}</div>
              </div>
            ))}
          </div>
        : <div style={{ position: 'absolute', left: X(coverX, cover), top: G.nextTop, width: cover, height: G.oneH, borderRadius: 18, background: HF.glassFill, border: `1px solid ${HF.glassBorder}`,
            display: 'flex', alignItems: 'center', gap: 10, padding: '0 12px 0 10px', boxSizing: 'border-box' }}>
            <Cover size={43} hue={PL_NEXT[0][2]} radius={10} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ ...HFType.sectionCaps, fontSize: 10, color: HF.text2 }}>{t('Next', 'Далее')}</div>
              <div className="hf-ellipsis" style={{ fontSize: 14, fontWeight: 700 }}>{PL_NEXT[0][0]}</div>
            </div>
            <Icon name="chevron_right" size={20} color={HF.text2} />
          </div>}
    </PlFrame>
  );
}

// ── 4. Player — Car layout ───────────────────────────────────────────────────
// `side` = the wheel's side ('left' = left-hand drive). `w` = screen width (1760 the user's unit, 1173 the wide split pane).
// `likes` 'buttons' | 'gestures'. The player never has a buttons bar. Per the user's drawing (2026-11-17):
//   top, wheel side   — one row: the queue dropdown → "Tune the wave" → search;
//   bottom, wheel side — title + progress with times + transport and the ring (moved down into the freed space);
//   far side          — today's car screen right side: the cover centred (larger), "Next" and three square cards
//                       (smaller) with title/artist under each, the clock in the corner.
// Everything mirrors for the right-hand wheel.
// Frameless body (the approved car player, plc-unit-1760). Also stands in for the old `CarHome` (see below), so
// every board that drew the old car screen now shows the new player: `queueName`/`queueLead` (the dropdown),
// `track` [title, artist, hue], `nextState`, `played`/`buffered`, `weak`, `dim`.
function PlCarBody({ side = 'left', w = FRAMES.head.w, h = 720, likes = 'buttons', burst, toast, drag, queueName, queueLead, track, nextState,
  played = 0.34, buffered = 0.6, weak, dim, next = 3, slot = 'wave', menu, browse }) {
  const [nowTitle, nowArtist, nowHue] = track || ['Wish You Were Here', 'Pink Floyd', 'amber'];
  const t = useT();
  const H = h;
  const at = (left, width) => (side === 'left' ? { left } : { left: w - left - width });
  const narrow = w < 1500;                                     // the wide split pane / smaller units
  const ring = narrow ? 230 : 290, knob = narrow ? 168 : 212;
  // Geometry from the shared rule (plWideLayout): the cover + next block spans the full height (top and bottom
  // padding = the ⋮ · queue row's top padding) at the far edge (minimum padding = the progress bar's edge padding);
  // the controls keep today's share of the width at most; extra width becomes spacing between the two sides.
  const G = plWideLayout({ W: w, H, next, big: true });
  const driverW = G.controlsW;
  const cardGap = G.cg, cardText = G.text, oneH = G.oneH;
  const coverSize = G.cover, card = G.card, coverTop = G.coverTop, coverLeft = G.coverLeft;
  const rowW = coverSize, rowLeft = coverLeft, nextTop = G.nextTop;
  const controlsTop = 150 + Math.max(0, (H - 720) / 2);           // taller screens: the controls stay centred
  return (
    <React.Fragment>
      {/* wheel side, top: ⋮ then the queue dropdown (everything else is in ⋮) */}
      <PlTopRow big menuOpen={menu} queueName={queueName} queueLead={queueLead} reverse={side !== 'left'}
        extra={weak ? <GlassPill icon="wifi_off" height={60} style={{ fontSize: 20, flexShrink: 0 }}>{t('Weak signal · downloaded', 'Слабая связь · скачанное')}</GlassPill> : null}
        style={{ top: 26, width: driverW, justifyContent: side === 'left' ? 'flex-start' : 'flex-start', ...(side === 'left' ? { left: 48 } : { right: 48 }) }} />
      {menu && <PlMoreMenu big slot={slot} browse={browse} gestures={likes === 'gestures'} style={{ top: 100, ...(side === 'left' ? { left: 48 } : { right: 48 }) }} />}
      {/* wheel side, bottom: labels centred between dislike / like, progress, transport with the ring */}
      <div style={{ position: 'absolute', top: controlsTop, width: driverW, ...at(48, driverW) }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 20 }}>
          {likes === 'buttons' ? <GlassCircle icon="heart_broken" size={80} iconSize={36} /> : <div style={{ width: 80, flex: '0 0 auto' }} />}
          <div style={{ flex: 1, minWidth: 0, textAlign: 'center' }}>
            <div style={{ ...HFType.sectionCaps, fontSize: 17, color: HF.text2 }}>{t('Queue · 12 of 48', 'Очередь · 12 из 48')}</div>
            <div className="hf-ellipsis" style={{ fontSize: narrow ? 38 : 46, lineHeight: narrow ? '46px' : '54px', fontWeight: 800 }}>{nowTitle}</div>
            <div className="hf-ellipsis" style={{ fontSize: 26, color: HF.text2 }}>{nowArtist}</div>
          </div>
          {likes === 'buttons' ? <GlassCircle icon="favorite" size={80} iconSize={36} color={HF.love} /> : <div style={{ width: 80, flex: '0 0 auto' }} />}
        </div>
        <div style={{ marginTop: 14 }}><PlProgress big width={driverW} played={played} buffered={buffered} /></div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 40, marginTop: 4, justifyContent: 'center' }}>
          <Knob icon="skip_previous" size={narrow ? 100 : 124} />
          <SpectrumRing size={ring} knob={knob} />
          <NextKnob state={nextState || 'ready'} size={narrow ? 100 : 124} />
        </div>
      </div>
      {/* far side: the cover centred in its block, above the next row */}
      <div style={{ position: 'absolute', top: coverTop, width: coverSize, height: coverSize, ...at(coverLeft, coverSize) }}>
        <Cover size={coverSize} hue={nowHue} radius={36} style={{ boxShadow: '0 0 90px rgba(224,160,80,0.35)' }} />
        {/* a faint radial scrim on the cover's far top corner, only when the clock sits on the cover */}
        {G.clock.onCover && <div style={{ position: 'absolute', inset: 0, borderRadius: 36, overflow: 'hidden', pointerEvents: 'none',
          background: `radial-gradient(ellipse at ${side === 'left' ? '100%' : '0%'} 0%, rgba(0,0,0,0.42) 0%, rgba(0,0,0,0.18) 30%, rgba(0,0,0,0) 55%)` }} />}
        {burst && <PlHeartBurst size={coverSize} />}
      </div>
      {/* the clock: fixed at the screen's top far corner (plWideLayout moves it inside the cover when it would sit
          across the cover's edge); white with a soft dark shadow so it reads on light and dark covers */}
      <div style={{ position: 'absolute', top: G.clock.y, width: G.clock.w, ...at(G.clock.x, G.clock.w), textAlign: side === 'left' ? 'right' : 'left',
        textShadow: '0 0 14px rgba(0,0,0,0.75), 0 2px 6px rgba(0,0,0,0.6)', whiteSpace: 'nowrap' }}>
        <div style={{ fontSize: 46, fontWeight: 800, lineHeight: '50px', color: '#fff' }}>10:30</div>
        <div style={{ ...HFType.sectionCaps, fontSize: 15, color: 'rgba(255,255,255,0.88)' }}>{t('Wed · 21 October', 'Среда · 21 октября')}</div>
      </div>
      {/* far side, under the cover at its width: one next card, or three small square cards */}
      {next === 3
        ? <div style={{ position: 'absolute', top: nextTop, width: rowW, display: 'flex', gap: cardGap, ...at(rowLeft, rowW) }}>
            {PL_NEXT.map(([a, b, hue], i) => (
              <div key={a} style={{ width: card, minWidth: 0 }}>
                {i === 0 && nextState ? <NextCover state={nextState} size={card} hue={hue} radius={18} /> : <Cover size={card} hue={hue} radius={18} />}
                <div className="hf-ellipsis" style={{ width: card, fontSize: 18, fontWeight: 700, marginTop: 6 }}>{a}</div>
                <div className="hf-ellipsis" style={{ width: card, fontSize: 15, color: HF.text2 }}>{b}</div>
              </div>
            ))}
          </div>
        : <div style={{ position: 'absolute', top: nextTop, width: rowW, height: oneH, borderRadius: 24, background: HF.glassFill, border: `1px solid ${HF.glassBorder}`,
            display: 'flex', alignItems: 'center', gap: 14, padding: '0 18px 0 12px', boxSizing: 'border-box', ...at(rowLeft, rowW) }}>
            {nextState ? <NextCover state={nextState} size={60} hue={PL_NEXT[0][2]} radius={14} /> : <Cover size={60} hue={PL_NEXT[0][2]} radius={14} />}
            <div style={{ flex: 1, minWidth: 0, textAlign: side === 'left' ? 'left' : 'right' }}>
              <div style={{ ...HFType.sectionCaps, fontSize: 14, color: HF.text2 }}>{t('Next', 'Далее')}</div>
              <div className="hf-ellipsis" style={{ fontSize: 20, fontWeight: 700 }}>{PL_NEXT[0][0]}</div>
            </div>
            <Icon name={side === 'left' ? 'chevron_right' : 'chevron_left'} size={28} color={HF.text2} />
          </div>}
      {toast && <div style={{ position: 'absolute', top: coverTop + coverSize - 90, width: coverSize, display: 'flex', justifyContent: 'center', ...at(coverLeft, coverSize) }}>{toast}</div>}
      {drag && (
        <React.Fragment>
          <div style={{ position: 'absolute', top: nextTop - 10, width: rowW + 28, height: (next === 3 ? card + cardText : oneH) + 20, borderRadius: 26, border: `3px dashed ${HF.accent}`, background: 'rgba(80,140,255,0.10)',
            ...at(rowLeft - 14, rowW + 28) }} />
          <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height: 14, background: 'rgba(80,140,255,0.45)' }} />
          <PlNote big icon="swipe_up" style={{ position: 'absolute', bottom: 30, ...(side === 'left' ? { left: 48 } : { right: 48 }) }}>
            {t('Swipe up from the bottom edge or the next tracks', 'Вверх от нижнего края или от следующих треков')}</PlNote>
        </React.Fragment>
      )}
      {dim && <Scrim opacity={0.6} />}
    </React.Fragment>
  );
}


function PlCarPlayer(props) {
  return <PlFrame w={props.w || FRAMES.head.w} h={props.h || 720} glowY="40%"><PlCarBody {...props} /></PlFrame>;
}

// The old car screen is gone: boards that still call `CarHome` (Phase 1–4 car boards, queue panels, Jellyfin car,
// the wide split pane) now render the new player body with their queue / track / Next state.
function PlCarHomeCompat({ dim, w = FRAMES.head.w, weak, pillLead, nextState, played = 0.3, buffered, pillName, track }) {
  return <PlCarBody w={w} dim={dim} weak={weak} queueLead={pillLead} queueName={pillName} track={track}
    nextState={nextState} played={played} buffered={buffered != null ? buffered : 0.6} />;
}
window.CarHome = PlCarHomeCompat;

function PlCarQueueOpen() {
  const t = useT();
  return (
    <PlFrame w={FRAMES.head.w} h={720} glowY="40%">
      <div style={{ position: 'absolute', left: 0, right: 0, top: 0, height: 120, display: 'flex', alignItems: 'center', gap: 22, padding: '0 48px' }}>
        <Cover size={84} hue="amber" radius={18} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="hf-ellipsis" style={{ fontSize: 30, fontWeight: 800 }}>Wish You Were Here</div>
          <div style={{ fontSize: 22, color: HF.text2 }}>Pink Floyd</div>
        </div>
        <Knob icon="pause" size={84} />
      </div>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 120, bottom: 0, borderRadius: '32px 32px 0 0', background: HF.sheet, border: `1px solid ${HF.hairline}` }}>
        <div style={{ width: 64, height: 6, borderRadius: 3, background: HF.text3, margin: '12px auto 10px' }} />
        <div style={{ display: 'flex', alignItems: 'center', gap: 24, padding: '0 48px 10px' }}>
          <PlDropdown big />
          <div style={{ ...HFType.sectionCaps, fontSize: 17, color: HF.text2 }}>{t('Next', 'Далее')}</div>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', padding: '0 48px' }}>
          {PL_NEXT.concat([['Time', 'Pink Floyd', 'teal'], ['Ветер', 'Мельница', 'rose'], ['Us and Them', 'Pink Floyd', 'blue']]).map(([a, b, hue]) => (
            <div key={a} style={{ display: 'flex', alignItems: 'center', gap: 18, height: 100 }}>
              <Cover size={76} hue={hue} radius={16} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="hf-ellipsis" style={{ fontSize: 26, fontWeight: 700 }}>{a}</div>
                <div className="hf-ellipsis" style={{ fontSize: 20, color: HF.text2 }}>{b}</div>
              </div>
              <Icon name="drag_handle" size={30} color={HF.text3} />
            </div>
          ))}
        </div>
      </div>
      <PlNote big icon="swipe_down" style={{ position: 'absolute', right: 48, bottom: 26 }}>{t('Swipe down — closes the queue first, then the player', 'Вниз — сначала закрывает очередь, потом плеер')}</PlNote>
    </PlFrame>
  );
}

// ── 6. Settings → Player ─────────────────────────────────────────────────────
function PlSettings() {
  const t = useT();
  const Row = ({ icon, title, sub, children, first }) => (
    <div style={{ padding: '12px 0', borderTop: first ? 'none' : `1px solid ${HF.hairline}` }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
        <IconPlaque icon={icon} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <Txt t="rowTitle">{title}</Txt>{sub && <Txt t="caption" color={HF.text2}>{sub}</Txt>}
        </div>
      </div>
      {children && <div style={{ marginTop: 10, marginLeft: 54 }}>{children}</div>}
    </div>
  );
  return (
    <SiSheetPage title={t('Player', 'Плеер')}>
      <SiCard>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, minHeight: 64, padding: '10px 0' }}>
          <IconPlaque icon="touch_app" />
          <div style={{ flex: 1, minWidth: 0 }}>
            <Txt t="rowTitle">{t('Large controls', 'Крупные кнопки')}</Txt>
            <Txt t="caption" color={HF.text2}>{t('Bigger buttons and the ring around Play — for the car. Chosen here, never automatically', 'Кнопки крупнее и кольцо вокруг Play — для машины. Выбирается здесь, никогда автоматически')}</Txt>
          </div>
          <Switch on={true} />
        </div>
        <Row icon="queue_music" title={t('Next tracks', 'Следующие треки')} sub={t('1: one row, tap opens the queue · 3: cards', '1: одна строка, нажатие открывает очередь · 3: карточки')}>
          <Segmented options={['1', '3']} value="3" />
        </Row>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, minHeight: 64, padding: '10px 0', borderTop: `1px solid ${HF.hairline}` }}>
          <IconPlaque icon="favorite" />
          <div style={{ flex: 1, minWidth: 0 }}>
            <Txt t="rowTitle">{t('Like by gestures', 'Лайк жестами')}</Txt>
            <Txt t="caption" color={HF.text2}>{t('Double tap the cover = like, hold = dislike; no buttons', 'Двойное нажатие на обложку = нравится, удержание = не нравится; без кнопок')}</Txt>
          </div>
          <Switch on={false} />
        </div>
      </SiCard>
      <SiCard style={{ marginTop: 10, padding: '12px 16px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Icon name="swap_horiz" size={22} color={HF.text2} />
          <Txt t="caption" color={HF.text2} style={{ flex: 1 }}>{t('The wheel’s side comes from Settings → Interface', 'Сторона руля — из Настройки → Интерфейс')}</Txt>
        </div>
      </SiCard>
    </SiSheetPage>
  );
}

function playerBoards() {
  const P = FRAMES.phone, H = FRAMES.head;
  const L = { w: FRAMES.land.w, h: FRAMES.land.h };
  return {
    'queues-phone': [
      { after: 'q-home', boards: [
        { id: 'pl-main', label: 'Main screen · the queue dropdown and Tune the wave at the top right, right-aligned; the "Player" link gone — a tap around Play or on the title opens the player; the "In car" tab becomes "Player"', ...P, el: <PlMainScreen /> },
      ] },
      { after: 'nb-mini', boards: [
        { id: 'pl-player-1', label: 'Player · portrait · top row queue · Tune (My Wave) · search · ⋮; centred title block with dislike / like beside it; Next tracks 1 (tap opens the queue) · swipe down closes the player, swipe up opens the queue', ...P, el: <PlPhonePlayer next={1} /> },
        { id: 'pl-player-3', label: 'Player · portrait · Next tracks 3: three compact cards in the next card’s place', ...P, el: <PlPhonePlayer next={3} /> },
        { id: 'pl-player-more', label: 'Player · portrait · ⋮ open on My Wave: Настроить волну · Поиск · Таймер сна · Добавить в плейлист… · Качество (likes only with like gestures on)', ...P, el: <PlPhonePlayer next={1} menu /> },
        { id: 'pl-player-long', label: 'Player · portrait · ⋮ then the queue dropdown; a long queue name truncates (icon and chevron kept)', ...P, el: <PlPhonePlayer next={1} queueName={'Вечерняя дорога домой после работы'} /> },
        { id: 'pl-player-radio', label: 'Player · portrait · ⋮ open on a normal Yandex queue: Запустить радио first (on Jellyfin no first item)', ...P, el: <PlPhonePlayer next={1} slot="radio" menu /> },
        { id: 'pl-drag', label: 'Player · swipe up: from the bottom edge or over the next track(s) (zones outlined); the queue rises', ...P, el: <PlPhonePlayer next={1} drag /> },
        { id: 'pl-queue', label: 'Player · queue open · swipe down closes the queue first; with the queue closed it closes the player', ...P, el: <PlPhoneQueueOpen /> },
        { id: 'pl-likes-gest', label: 'Like gestures (variant ii) · no like buttons; double tap on the cover = like, a heart burst', ...P,
          el: <PlPhonePlayer next={1} likes="gestures" burst /> },
        { id: 'pl-dislike-gest', label: 'Like gestures · hold the cover = dislike, with a confirmation (Undo)', ...P,
          el: <PlPhonePlayer next={1} likes="gestures" toast={<PlToast icon="heart_broken" text={t2('Disliked — won’t play again', 'Не нравится — больше не прозвучит')} action={t2('Undo', 'Отменить')} />} /> },
        { id: 'pl-land-1', label: 'Player · landscape · the car arrangement: ⋮ · queue on top (everything else in ⋮), labels between dislike / like, transport; the cover + next track block spans the full height at the far edge', ...L, el: <PlPhoneLandscape next={1} /> },
        { id: 'pl-land-more', label: 'Player · landscape · ⋮ open on My Wave (left-hand: ⋮ · queue at the top-left)', ...L, el: <PlPhoneLandscape next={1} menu /> },
        { id: 'pl-land-radio', label: 'Player · landscape · right-hand: queue · ⋮ at the top-right, mirrored; ⋮ open on a normal queue (Запустить радио first)', ...L, el: <PlPhoneLandscape next={1} slot="radio" menu side="right" /> },
        { id: 'pl-land-3', label: 'Player · landscape · Next tracks 3: three small square cards under the cover', ...L, el: <PlPhoneLandscape next={3} /> },
      ] },
    ],
    'queues-car': [
      { after: 'nbc-start', boards: [
        { id: 'plc-bottom-l', label: 'Player · Car layout · left-hand wheel · no buttons bar; one top-left row: queue → Tune the wave → search; labels centred between dislike / like; title, progress and controls moved down; cover moved right (where Next was); next 3 under the cover', ...H, el: <PlCarPlayer /> },
        { id: 'plc-bottom-r', label: 'Player · Car layout · right-hand wheel · the same, mirrored', ...H, el: <PlCarPlayer side="right" /> },
        { id: 'plc-unit-1760', label: 'Player · Car layout on the user’s unit (1760×720, navigation panel on the left) · the cover + next block spans the full height (top and bottom padding = the ⋮ · queue row’s top padding); the clock has one fixed position on every screen (right padding + 20 dp, cover top padding + 20 dp) — inside the cover on taller screens, over or beside it on wider ones; white with a soft shadow', w: 1760, h: 720, el: <PlCarPlayer w={1760} /> },
        { id: 'plc-clock-light', label: 'Player · Car · the clock over a light cover (shadow + corner scrim keep it readable)', w: 1760, h: 720, el: <PlCarPlayer w={1760} track={['Morning Light', 'Sunday Trio', 'sand']} /> },
        { id: 'plc-clock-dark', label: 'Player · Car · the clock over a dark cover', w: 1760, h: 720, el: <PlCarPlayer w={1760} track={['Midnight', 'The Band', 'ink']} /> },
        { id: 'plc-tall-1280', label: 'Player · Car · the sizing rule on a taller landscape screen (1280×800): the cover keeps the full height, the controls side gets narrower', w: 1280, h: 800, el: <PlCarPlayer w={1280} h={800} /> },
        { id: 'plc-wide-2400', label: 'Player · Car · the sizing rule on a very wide screen (2400×720): the controls keep today’s share of the width at most, the cover is sized from the height and centred in the space beside the controls', w: 2400, h: 720, el: <PlCarPlayer w={2400} /> },
        { id: 'plc-more', label: 'Player · Car · left-hand · ⋮ open on My Wave (car sizes): Настроить волну · Поиск · Таймер сна · Добавить в плейлист… · Качество', w: 1760, h: 720, el: <PlCarPlayer w={1760} menu /> },
        { id: 'plc-radio', label: 'Player · Car · right-hand · queue · ⋮ at the top-right; ⋮ open on a normal queue (Запустить радио first)', w: 1760, h: 720, el: <PlCarPlayer w={1760} side="right" slot="radio" menu /> },
        { id: 'plc-next-1', label: 'Player · Car layout · Next tracks 1: one next card under the cover, at the cover’s width', ...H, el: <PlCarPlayer next={1} /> },
        { id: 'plc-likes-gest', label: 'Car · like gestures (variant ii): no like buttons; double tap on the cover = like', ...H, el: <PlCarPlayer likes="gestures" burst /> },
        { id: 'plc-dislike-gest', label: 'Car · hold the cover = dislike, confirmation with Undo', ...H,
          el: <PlCarPlayer likes="gestures" toast={<PlToast big icon="heart_broken" text={t2('Disliked', 'Не нравится')} action={t2('Undo', 'Отменить')} />} /> },
        { id: 'plc-queue', label: 'Car · swipe up = queue: from the bottom edge or over the next tracks (zones outlined)', ...H, el: <PlCarPlayer drag /> },
        { id: 'plc-queue-open', label: 'Car · queue open · one column (scrolls) · swipe down closes the queue first, then the player', ...H, el: <PlCarQueueOpen /> },
      ] },
    ],
    'si-signin': [
      { after: 'qa-playback', boards: [
        { id: 'pl-settings', label: 'Settings → Player · Large controls (on = the car sizes, never automatic), Next tracks 1 / 3, Like by gestures', ...P, el: <PlSettings /> },
      ] },
    ],
  };
}

// Board labels are built outside a component; texts inside elements need the current language at render time.
function t2(en, ru) { return <PlT en={en} ru={ru} />; }
function PlT({ en, ru }) { const t = useT(); return t(en, ru); }

Object.assign(window, { playerBoards, PlMainScreen, PlMainContent, PlTabBar, PlDropdown, PlMoreMenu, PlNote, PlPhonePlayer, PlPhoneQueueOpen, PlPhoneLandscape, PlCarPlayer, PlCarQueueOpen, PlSettings });
