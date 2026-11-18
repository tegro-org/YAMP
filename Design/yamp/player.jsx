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
      {lead || <Icon name="stacks" size={big ? 28 : 20} />}
      <span className="hf-ellipsis" style={{ maxWidth: big ? 300 : 180 }}>{name || t('With family', 'С семьёй')}</span>
      <Icon name="expand_more" size={big ? 28 : 20} color={HF.text2} />
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
  const t = useT();
  return (
    <Frame kind="phone" tint="#2C4FA8" glowY="26%">
      <div style={{ position: 'absolute', left: 20, top: 22 }}><LargeTitle>{t('My Wave', 'Моя волна')}</LargeTitle></div>
      <div style={{ position: 'absolute', right: 20, top: 18, display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 8 }}>
        <PlDropdown />
        <GlassPill icon="tune" height={36}>{t('Tune the wave', 'Настроить волну')}</GlassPill>
      </div>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 176, display: 'flex', justifyContent: 'center' }}><SpectrumRing size={290} knob={164} /></div>
      <PlTarget left={56} top={168} width={300} height={306} radius={150} />
      <div style={{ position: 'absolute', left: 20, right: 20, top: 488, display: 'flex', alignItems: 'center', gap: 12 }}>
        <GlassCircle icon="heart_broken" size={48} />
        <div style={{ flex: 1, textAlign: 'center', minWidth: 0 }}>
          <div className="hf-ellipsis" style={{ fontSize: 24, fontWeight: 800 }}>{t('First song', 'Первая песня')}</div>
          <Txt t="body" color={HF.text2}>{t('The Band', 'Группа')}</Txt>
        </div>
        <GlassCircle icon="favorite" size={48} color={HF.love} />
      </div>
      <PlTarget left={82} top={482} width={248} height={62} radius={18} />
      <SectionCaps style={{ position: 'absolute', left: 20, right: 12, top: 566 }}>{t('Next in the wave', 'Дальше в волне')}</SectionCaps>
      <div style={{ position: 'absolute', left: 20, top: 604, display: 'flex', gap: 12 }}>
        {[['A very long song title', 'Artist with a long name', 'rose'], [t('Third song', 'Третья песня'), t('Artist', 'Исполнитель'), 'green']].map(([a, b, hue]) => (
          <div key={a} style={{ width: 120 }}>
            <Cover size={120} hue={hue} radius={18} />
            <Txt t="cardTitle" ellipsis style={{ marginTop: 6 }}>{a}</Txt><Txt t="caption" color={HF.text2} ellipsis>{b}</Txt>
          </div>
        ))}
      </div>
      <PlNote style={{ position: 'absolute', left: 20, top: 78 }}>{t('Tap = player', 'Нажатие = плеер')}</PlNote>
      <PlNote icon="arrow_downward" style={{ position: 'absolute', right: 20, bottom: 96 }}>{t('"In car" tab → "Player"', 'Вкладка «В авто» → «Плеер»')}</PlNote>
      <PlTabBar mark />
    </Frame>
  );
}

// ── 2. Player — Phone layout, portrait ───────────────────────────────────────
// As built + the next tracks: `next` 1 (one row, tap → queue) or 3 (cards). `likes` 'buttons' | 'gestures'.
// `drag` = a mid-drag frame of the swipe up (the queue rising).
function PlPhonePlayer({ next = 1, likes = 'buttons', burst, toast, drag }) {
  // Positions follow today's player (golden next_full_player_*, 539 px → 412 dp): top bar, cover 327, title row,
  // format chip, slider with times, transport row, the next-track card at the bottom. "3" replaces that card with a
  // row of three compact cards in the same place.
  const t = useT();
  const cover = 327, coverTop = 132;
  return (
    <Frame kind="phone" tint="#2C4FA8" glowY="22%">
      <div style={{ position: 'absolute', inset: 0, transform: drag ? 'translateY(-170px)' : 'none' }}>
        <GlassCircle icon="expand_more" size={44} style={{ position: 'absolute', left: 21, top: 10 }} />
        <div style={{ position: 'absolute', left: 90, right: 120, top: 12, textAlign: 'center' }}>
          <div style={{ ...HFType.sectionCaps, fontSize: 12, color: HF.text2 }}>{t('Now playing', 'Сейчас играет')}</div>
          <div className="hf-ellipsis" style={{ fontSize: 13, color: HF.text }}>{t('My Wave', 'Моя волна')}</div>
        </div>
        <GlassCircle icon="bedtime" size={44} style={{ position: 'absolute', left: 296, top: 10 }} />
        <GlassCircle icon="queue_music" size={44} style={{ position: 'absolute', left: 348, top: 10 }} />
        <div style={{ position: 'absolute', left: 43, top: coverTop, width: cover, height: cover }}>
          <Cover size={cover} hue="blue" radius={26} style={{ boxShadow: '0 0 60px rgba(60,90,200,0.25)' }} />
          {burst && <PlHeartBurst size={cover} />}
        </div>
        <div style={{ position: 'absolute', left: 21, right: 66, top: 540 }}>
          <div className="hf-ellipsis" style={{ fontSize: 23, lineHeight: '28px', fontWeight: 800 }}>{t('First song', 'Первая песня')}</div>
          <div style={{ fontSize: 16, color: HF.text2 }}>{t('The Band', 'Группа')}</div>
        </div>
        <GlassCircle icon="playlist_add" size={44} style={{ position: 'absolute', left: 348, top: 543 }} />
        <div style={{ position: 'absolute', left: 21, top: 607, height: 27, padding: '0 12px', borderRadius: 14, background: 'rgba(0,0,0,0.45)',
          border: `1px solid ${HF.hairline}`, display: 'flex', alignItems: 'center', fontSize: 11, fontWeight: 800, letterSpacing: 0.6 }}>{t('MP3 · 320 KBPS', 'MP3 · 320 КБИТ/С')}</div>
        <div style={{ position: 'absolute', left: 21, right: 21, top: 657 }}>
          <BufferSlider played={0.38} buffered={0.62} />
          <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 4, fontSize: 13, color: HF.text2 }}><span>1:22</span><span>−2:13</span></div>
        </div>
        <div style={{ position: 'absolute', left: 21, right: 21, top: 720, height: 96, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          {likes === 'buttons' ? <GlassCircle icon="heart_broken" size={52} /> : <div style={{ width: 52 }} />}
          <Knob icon="skip_previous" size={68} />
          <Knob icon="pause" size={95} />
          <NextKnob state="ready" size={68} />
          {likes === 'buttons' ? <GlassCircle icon="favorite" size={52} color={HF.love} /> : <div style={{ width: 52 }} />}
        </div>
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
function PlNextStrip() {
  return (
    <div style={{ position: 'absolute', left: 21, right: 21, top: 837, height: 63, display: 'flex', gap: 8 }}>
      {PL_NEXT.map(([a, b, hue]) => (
        <div key={a} style={{ flex: 1, minWidth: 0, borderRadius: 16, background: HF.glassFill, border: `1px solid ${HF.glassBorder}`,
          display: 'flex', alignItems: 'center', gap: 8, padding: '0 8px' }}>
          <Cover size={43} hue={hue} radius={10} />
          <div style={{ minWidth: 0 }}>
            <div className="hf-ellipsis" style={{ fontSize: 12, fontWeight: 700 }}>{a}</div>
            <div className="hf-ellipsis" style={{ fontSize: 11, color: HF.text2 }}>{b}</div>
          </div>
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
function PlPhoneLandscape({ next = 1 }) {
  // The approved car arrangement adapted to phone landscape: top row queue · tune · search; the labels centred
  // between dislike / like; progress with times; Prev · Play · Next. The far side: the cover centred, the next
  // track(s) under it (1 = one card, 3 = three small square cards). Laid out at 960×540 dp and drawn at 2× on a
  // 1920×1080 board (FRAMES.land) — a 1080p phone in landscape.
  const W = 960, H = 540, S = FRAMES.land.w / W;
  return (
    <div style={{ width: FRAMES.land.w, height: FRAMES.land.h, overflow: 'hidden' }}>
      <div style={{ width: W, height: H, transform: `scale(${S})`, transformOrigin: '0 0' }}>
        <PlPhoneLandscapeBody next={next} W={W} H={H} />
      </div>
    </div>
  );
}

function PlPhoneLandscapeBody({ next, W, H }) {
  const t = useT();
  const leftW = 540, farX = 24 + leftW + 30, farW = W - farX - 24;
  const cover = next === 3 ? 300 : 330;
  const card = Math.floor((cover - 2 * 10) / 3);
  const farH = next === 3 ? cover + 12 + card + 20 : cover + 14 + 63;
  const farTop = Math.max(16, (H - farH) / 2);
  return (
    <PlFrame w={W} h={H} tint="#2C4FA8" glowY="30%">
      <div style={{ position: 'absolute', left: 24, top: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
        <PlDropdown />
        <GlassPill icon="tune" height={44}>{t('Tune the wave', 'Настроить волну')}</GlassPill>
        <GlassCircle icon="search" size={44} />
      </div>
      <div style={{ position: 'absolute', left: 24, top: 150, width: leftW }}>
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
      <div style={{ position: 'absolute', left: farX + (farW - cover) / 2, top: farTop }}><Cover size={cover} hue="blue" radius={24} /></div>
      {next === 3
        ? <div style={{ position: 'absolute', left: farX + (farW - cover) / 2, top: farTop + cover + 12, width: cover, display: 'flex', gap: 10 }}>
            {PL_NEXT.map(([a, , hue]) => (
              <div key={a} style={{ width: card }}>
                <Cover size={card} hue={hue} radius={14} />
                <div className="hf-ellipsis" style={{ fontSize: 12, fontWeight: 700, marginTop: 4 }}>{a}</div>
              </div>
            ))}
          </div>
        : <div style={{ position: 'absolute', left: farX + (farW - cover) / 2, top: farTop + cover + 14, width: cover, height: 63, borderRadius: 18, background: HF.glassFill, border: `1px solid ${HF.glassBorder}`,
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
function PlCarBody({ side = 'left', w = FRAMES.head.w, likes = 'buttons', burst, toast, drag, queueName, queueLead, track, nextState,
  played = 0.34, buffered = 0.6, weak, dim, next = 3 }) {
  const [nowTitle, nowArtist, nowHue] = track || ['Wish You Were Here', 'Pink Floyd', 'amber'];
  const t = useT();
  const H = 720;
  const at = (left, width) => (side === 'left' ? { left } : { left: w - left - width });
  const narrow = w < 1500;                                     // the wide split pane
  const driverW = narrow ? 600 : 820, ring = narrow ? 230 : 290, knob = narrow ? 168 : 212;
  const farX = 48 + driverW + 50, farW = w - farX - 48;
  // Far side = the phone-landscape player's block scaled to 720 px (pl-land-*): the cover takes most of the height,
  // the next block sits under it at the cover's width — "1": one next card, "3": three small square cards spanning
  // the cover's width, title under each. The block starts below the clock in the far corner.
  const cardGap = 14, cardText = 50, oneH = 84;
  const coverSize = Math.min(next === 3 ? 380 : 440, farW);
  const card = Math.floor((coverSize - 2 * cardGap) / 3);
  const blockH = next === 3 ? coverSize + 16 + card + cardText : coverSize + 18 + oneH;
  const coverTop = Math.max(108, (H - blockH) / 2);
  const coverLeft = farX + (farW - coverSize) / 2;
  const rowW = coverSize, rowLeft = coverLeft, nextTop = coverTop + coverSize + (next === 3 ? 16 : 18);
  return (
    <React.Fragment>
      {/* wheel side, top: the queue dropdown and "Tune the wave" */}
      <div style={{ position: 'absolute', top: 26, display: 'flex', alignItems: 'center', gap: 14, flexDirection: side === 'left' ? 'row' : 'row-reverse', ...(side === 'left' ? { left: 48 } : { right: 48 }) }}>
        <PlDropdown big name={queueName} lead={queueLead} />
        {weak
          ? <GlassPill icon="wifi_off" height={60} style={{ fontSize: 20 }}>{t('Weak signal · downloaded', 'Слабая связь · скачанное')}</GlassPill>
          : <GlassPill icon="tune" height={60} style={{ fontSize: 20 }}>{t('Tune the wave', 'Настроить волну')}</GlassPill>}
        <GlassCircle icon="search" size={60} />
      </div>
      {/* far side, top: clock and search beside the cover */}
      <div style={{ position: 'absolute', top: 26, width: 260, ...at(w - 48 - 260, 260), textAlign: side === 'left' ? 'right' : 'left' }}>
        <div style={{ fontSize: 46, fontWeight: 800, lineHeight: '50px' }}>10:30</div>
        <div style={{ ...HFType.sectionCaps, fontSize: 15, color: HF.text2 }}>{t('Wed · 21 October', 'Среда · 21 октября')}</div>
      </div>
      {/* wheel side, bottom: labels centred between dislike / like, progress, transport with the ring */}
      <div style={{ position: 'absolute', top: 150, width: driverW, ...at(48, driverW) }}>
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
        {burst && <PlHeartBurst size={coverSize} />}
      </div>
      {/* far side, under the cover at its width: one next card, or three small square cards */}
      {next === 3
        ? <div style={{ position: 'absolute', top: nextTop, width: rowW, display: 'flex', gap: cardGap, flexDirection: side === 'left' ? 'row' : 'row-reverse', ...at(rowLeft, rowW) }}>
            {PL_NEXT.map(([a, b, hue], i) => (
              <div key={a} style={{ width: card }}>
                {i === 0 && nextState ? <NextCover state={nextState} size={card} hue={hue} radius={18} /> : <Cover size={card} hue={hue} radius={18} />}
                <div className="hf-ellipsis" style={{ fontSize: 18, fontWeight: 700, marginTop: 6 }}>{a}</div>
                <div className="hf-ellipsis" style={{ fontSize: 15, color: HF.text2 }}>{b}</div>
              </div>
            ))}
          </div>
        : <div style={{ position: 'absolute', top: nextTop, width: rowW, height: oneH, borderRadius: 24, background: HF.glassFill, border: `1px solid ${HF.glassBorder}`,
            display: 'flex', alignItems: 'center', gap: 14, padding: '0 18px 0 12px', boxSizing: 'border-box', flexDirection: side === 'left' ? 'row' : 'row-reverse', ...at(rowLeft, rowW) }}>
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
  return <PlFrame w={props.w || FRAMES.head.w} h={720} glowY="40%"><PlCarBody {...props} /></PlFrame>;
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
        <Row first icon="devices" title={t('Layout', 'Вид')} sub={t('Chosen here, never automatically', 'Выбирается здесь, никогда автоматически')}>
          <Segmented options={[t('Phone', 'Телефон'), t('Car', 'Автомобиль')]} value={t('Car', 'Автомобиль')} />
        </Row>
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
        { id: 'pl-player-1', label: 'Player · Phone layout, portrait · Next tracks 1 = today’s player as is (one next track, tap opens the queue)', ...P, el: <PlPhonePlayer next={1} /> },
        { id: 'pl-player-3', label: 'Player · Next tracks 3 = a card row like the main screen / car mode: current cover large, three smaller next cards', ...P, el: <PlPhonePlayer next={3} /> },
        { id: 'pl-drag', label: 'Player · swipe up: from the bottom edge or over the next track(s) (zones outlined); the queue rises', ...P, el: <PlPhonePlayer next={1} drag /> },
        { id: 'pl-queue', label: 'Player · queue open · swipe down closes the queue first; with the queue closed it closes the player', ...P, el: <PlPhoneQueueOpen /> },
        { id: 'pl-likes-gest', label: 'Like gestures (variant ii) · no like buttons; double tap on the cover = like, a heart burst', ...P,
          el: <PlPhonePlayer next={1} likes="gestures" burst /> },
        { id: 'pl-dislike-gest', label: 'Like gestures · hold the cover = dislike, with a confirmation (Undo)', ...P,
          el: <PlPhonePlayer next={1} likes="gestures" toast={<PlToast icon="heart_broken" text={t2('Disliked — won’t play again', 'Не нравится — больше не прозвучит')} action={t2('Undo', 'Отменить')} />} /> },
        { id: 'pl-land-1', label: 'Player · landscape · the car arrangement: queue · tune · search on top, labels between dislike / like, transport; cover with the next track under it', ...L, el: <PlPhoneLandscape next={1} /> },
        { id: 'pl-land-3', label: 'Player · landscape · Next tracks 3: three small square cards under the cover', ...L, el: <PlPhoneLandscape next={3} /> },
      ] },
    ],
    'queues-car': [
      { after: 'nbc-start', boards: [
        { id: 'plc-bottom-l', label: 'Player · Car layout · left-hand wheel · no buttons bar; one top-left row: queue → Tune the wave → search; labels centred between dislike / like; title, progress and controls moved down; cover moved right (where Next was); next 3 under the cover', ...H, el: <PlCarPlayer /> },
        { id: 'plc-bottom-r', label: 'Player · Car layout · right-hand wheel · the same, mirrored', ...H, el: <PlCarPlayer side="right" /> },
        { id: 'plc-unit-1760', label: 'Player · Car layout on the user’s unit (1760×720, navigation panel on the left)', w: 1760, h: 720, el: <PlCarPlayer w={1760} /> },
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
        { id: 'pl-settings', label: 'Settings → Player · Layout Phone / Car (no automatic logic), Next tracks 1 / 3, Like by gestures', ...P, el: <PlSettings /> },
      ] },
    ],
  };
}

// Board labels are built outside a component; texts inside elements need the current language at render time.
function t2(en, ru) { return <PlT en={en} ru={ru} />; }
function PlT({ en, ru }) { const t = useT(); return t(en, ru); }

Object.assign(window, { playerBoards, PlMainScreen, PlPhonePlayer, PlPhoneQueueOpen, PlPhoneLandscape, PlCarPlayer, PlCarQueueOpen, PlSettings });
