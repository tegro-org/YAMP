// queues.jsx — Phase 1: multiple queues. Queue switcher popup (D8, D26), queue actions, "Add to queue…".
// Phone: sheet. Landscape / car: side panel on the driver's side. Switching = 2 taps (open, pick).

const KIND_ICON = { static: 'queue_music', wave: 'waves', radio: 'sensors', mix: 'auto_awesome' };

function useQueues() {
  const t = useT();
  return [
    { id: 'family', kind: 'static', name: t('With family', 'С семьёй'), track: 'Wish You Were Here', artist: 'Pink Floyd', hue: 'amber', pos: 12, total: 48, progress: 0.42, active: true },
    { id: 'solo', kind: 'static', name: t('Solo drive', 'Один за рулём'), track: 'Du hast', artist: 'Rammstein', hue: 'rose', pos: 3, total: 112, progress: 0.71 },
    { id: 'book', kind: 'static', name: t('Audiobook', 'Аудиокнига'), track: t('Chapter 14', 'Глава 14'), artist: t('Roadside Picnic', 'Пикник на обочине'), hue: 'teal', pos: 14, total: 31, progress: 0.18 },
    { id: 'wave', kind: 'wave', name: t('My Wave', 'Моя волна'), track: t('First song', 'Первая песня'), artist: t('The Band', 'Группа'), hue: 'blue', progress: 0.3 },
    { id: 'radio', kind: 'radio', name: t('Radio: Time', 'Радио: Time'), track: 'Echoes', artist: 'Pink Floyd', hue: 'violet', pos: 7, total: 25, progress: 0.55 },
  ];
}

function QueueRow({ q, big }) {
  const h = big ? 100 : 78, cover = big ? 68 : 52;
  return (
    <div style={{ height: h, borderRadius: big ? 24 : 18, padding: big ? '0 20px 0 16px' : '0 14px 0 12px', display: 'flex', alignItems: 'center', gap: big ? 18 : 12,
      background: q.active ? `linear-gradient(180deg, ${HF.anodLight} 0%, ${HF.anodDark} 100%)` : 'transparent',
      boxShadow: q.active ? 'inset 0 1px 0 rgba(255,255,255,0.20), 0 0 0 1.5px rgba(201,206,216,0.50)' : 'none' }}>
      <div style={{ position: 'relative' }}>
        <Cover size={cover} hue={q.hue} radius={big ? 14 : 10} />
        <div style={{ position: 'absolute', right: -6, bottom: -6, width: big ? 28 : 22, height: big ? 28 : 22, borderRadius: 14, background: HF.sheet,
          border: `1px solid ${HF.glassBorder}`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name={KIND_ICON[q.kind]} size={big ? 17 : 14} color={HF.text2} />
        </div>
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="hf-ellipsis" style={{ fontSize: big ? 24 : 17, lineHeight: big ? '30px' : '22px', fontWeight: 700 }}>{q.name}</div>
        <div className="hf-ellipsis" style={{ fontSize: big ? 17 : 13, lineHeight: big ? '22px' : '17px', color: HF.text2 }}>{q.track} · {q.artist}</div>
        <ProgressBar value={q.progress} height={big ? 4 : 3} color={q.active ? '#fff' : 'rgba(255,255,255,0.55)'} style={{ marginTop: big ? 10 : 7 }} />
      </div>
      <div style={{ minWidth: big ? 84 : 58, textAlign: 'right' }}>
        {q.active
          ? <Icon name="graphic_eq" size={big ? 34 : 24} />
          : <div style={{ fontSize: big ? 17 : 13, color: HF.text3 }}>{q.total ? `${q.pos} / ${q.total}` : '∞'}</div>}
      </div>
    </div>
  );
}

// ── Phone ────────────────────────────────────────────────────────────────────
function SpectrumRing({ size = 330, knob = 216, icon = 'pause' }) {
  // Tick lengths are proportional to the room between the knob and the box, so the ring never clips.
  const n = 72, c = size / 2, r0 = knob / 2 + 10, room = size / 2 - r0 - 2;
  const ticks = [];
  for (let i = 0; i < n; i++) {
    const a = (i / n) * Math.PI * 2 - Math.PI / 2;
    const len = room * (0.22 + 0.5 * Math.abs(Math.sin(i * 1.7) * Math.cos(i * 0.37)) + (i < 20 ? 0.26 : 0));
    const o = i < 22 ? 1 : 0.35;
    ticks.push(<line key={i} x1={c + Math.cos(a) * r0} y1={c + Math.sin(a) * r0} x2={c + Math.cos(a) * (r0 + len)} y2={c + Math.sin(a) * (r0 + len)}
      stroke="#fff" strokeOpacity={o} strokeWidth={Math.max(2, knob / 70)} strokeLinecap="round" />);
  }
  return (
    <div style={{ position: 'relative', width: size, height: size }}>
      <svg width={size} height={size} style={{ position: 'absolute', inset: 0 }}>{ticks}</svg>
      <Knob icon={icon} size={knob} iconSize={Math.round(knob * 0.3)} style={{ position: 'absolute', left: (size - knob) / 2, top: (size - knob) / 2 }} />
    </div>
  );
}

// Home (My Wave) as built, plus the queue button top-right.
function PhoneHome({ dim }) {
  const t = useT(); const queues = useQueues();
  return (
    <React.Fragment>
      <div style={{ position: 'absolute', left: 20, right: 20, top: 22, display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
        <div>
          <LargeTitle>{t('My Wave', 'Моя волна')}</LargeTitle>
          <GlassPill icon="tune" height={36} style={{ marginTop: 12, display: 'inline-flex' }}>{t('Tune the wave', 'Настроить волну')}</GlassPill>
        </div>
        <GlassCircle icon="stacks" badge={queues.length} />
      </div>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 150, display: 'flex', justifyContent: 'center' }}><SpectrumRing size={300} knob={170} /></div>
      <div style={{ position: 'absolute', left: 20, right: 20, top: 470, display: 'flex', alignItems: 'center', gap: 12 }}>
        <GlassCircle icon="heart_broken" size={48} />
        <div style={{ flex: 1, textAlign: 'center', minWidth: 0 }}>
          <div className="hf-ellipsis" style={{ fontSize: 24, fontWeight: 800 }}>{t('First song', 'Первая песня')}</div>
          <Txt t="body" color={HF.text2}>{t('The Band', 'Группа')}</Txt>
        </div>
        <GlassCircle icon="favorite" size={48} color={HF.love} />
      </div>
      <SectionCaps style={{ position: 'absolute', left: 20, right: 12, top: 552 }}
        trailing={<TextAction>{t('Player', 'Плеер')}<Icon name="chevron_right" size={20} /></TextAction>}>{t('Next in the wave', 'Дальше в волне')}</SectionCaps>
      <div style={{ position: 'absolute', left: 20, top: 596, display: 'flex', gap: 12 }}>
        {[['A very long song title', 'Artist with a long name', 'rose'], [t('Third song', 'Третья песня'), t('Artist', 'Исполнитель'), 'green']].map(([a, b, hue]) => (
          <div key={a} style={{ width: 132 }}>
            <Cover size={132} hue={hue} radius={18} />
            <Txt t="cardTitle" ellipsis style={{ marginTop: 8 }}>{a}</Txt><Txt t="caption" color={HF.text2} ellipsis>{b}</Txt>
          </div>
        ))}
      </div>
      <PhoneChrome active="home" />
      {dim && <Scrim />}
    </React.Fragment>
  );
}

function QueueSheetHeader({ title, subtitle, big, trailing }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: big ? '26px 28px 14px' : '16px 20px 10px' }}>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: big ? 36 : 26, lineHeight: big ? '42px' : '32px', fontWeight: 800 }}>{title}</div>
        {subtitle && <div style={{ fontSize: big ? 17 : 13, color: HF.text2, marginTop: 2 }}>{subtitle}</div>}
      </div>
      <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>{trailing}<GlassCircle icon="close" size={big ? 60 : 44} /></div>
    </div>
  );
}

function PhoneQueueSheet() {
  const t = useT(); const queues = useQueues();
  return (
    <Frame kind="phone">
      <PhoneHome dim />
      <Sheet height={640}>
        <QueueSheetHeader title={t('Queues', 'Очереди')} subtitle={t('Tap to switch · hold for more', 'Нажмите, чтобы переключить · удерживайте для действий')} />
        <div style={{ padding: '0 8px', display: 'flex', flexDirection: 'column', gap: 4 }}>
          {queues.map((q) => <QueueRow key={q.id} q={q} />)}
        </div>
        <div style={{ position: 'absolute', left: 20, right: 20, bottom: 24 }}><GlassPill icon="add">{t('New queue', 'Новая очередь')}</GlassPill></div>
      </Sheet>
    </Frame>
  );
}

function PhoneQueueMenu({ radio }) {
  const t = useT(); const queues = useQueues(); const q = radio ? queues[4] : queues[1];
  if (radio) return (
    <Frame kind="phone">
      <PhoneHome dim />
      <Sheet height={640} style={{ opacity: 0.35 }} />
      <Sheet height={330}>
        <div style={{ padding: '18px 24px 0' }}>
          <Txt t="dialogTitle">{q.name}</Txt>
          <Txt t="caption" color={HF.text2} style={{ marginBottom: 10 }}>{t('Radio · replaced when you start another radio', 'Радио · заменяется, когда вы включаете другое радио')}</Txt>
          <MenuRow icon="bookmark_add">{t('Keep as a queue…', 'Сохранить как очередь…')}</MenuRow>
          <MenuRow icon="sensors">{t('Start again from the same seed', 'Начать заново с того же')}</MenuRow>
          <MenuRow icon="delete" color={HF.danger}>{t('Delete radio', 'Удалить радио')}</MenuRow>
        </div>
      </Sheet>
    </Frame>
  );
  return (
    <Frame kind="phone">
      <PhoneHome dim />
      <Sheet height={640} style={{ opacity: 0.35 }} />
      <Sheet height={430}>
        <div style={{ padding: '18px 24px 0' }}>
          <Txt t="dialogTitle">{q.name}</Txt>
          <Txt t="caption" color={HF.text2} style={{ marginBottom: 10 }}>{t('112 tracks · paused at 3', '112 треков · остановлена на 3-м')}</Txt>
          <MenuRow icon="edit">{t('Rename', 'Переименовать')}</MenuRow>
          <MenuRow icon="content_copy">{t('Duplicate', 'Дублировать')}</MenuRow>
          <MenuRow icon="playlist_add">{t('Save as playlist', 'Сохранить как плейлист')}</MenuRow>
          <MenuRow icon="playlist_remove">{t('Clear', 'Очистить')}</MenuRow>
          <MenuRow icon="delete" color={HF.danger}>{t('Delete queue', 'Удалить очередь')}</MenuRow>
        </div>
      </Sheet>
    </Frame>
  );
}

function PhoneQueueNew() {
  const t = useT();
  return (
    <Frame kind="phone">
      <PhoneHome dim /><Scrim opacity={0.3} />
      <Dialog title={t('New queue', 'Новая очередь')} width={340}
        actions={<React.Fragment><DialogAction>{t('Cancel', 'Отмена')}</DialogAction><DialogAction kind="primary">{t('Create', 'Создать')}</DialogAction></React.Fragment>}>
        <Field value={t('Weekend trip', 'Поездка на выходные')} style={{ marginTop: 16 }} />
        <Txt t="caption" color={HF.text3} style={{ marginTop: 10 }}>{t('Starts empty. Add music with “Add to queue…”, or make it active and play something.', 'Создаётся пустой. Добавляйте музыку через «Добавить в очередь…» или сделайте её активной и включите что-нибудь.')}</Txt>
      </Dialog>
    </Frame>
  );
}

function PhoneQueueDelete() {
  const t = useT();
  return (
    <Frame kind="phone">
      <PhoneHome dim /><Scrim opacity={0.3} />
      <Dialog title={t('Delete queue?', 'Удалить очередь?')} width={340}
        text={t('“Solo drive” and its 112 tracks will be removed from the app. The music itself is not deleted.', '«Один за рулём» и 112 треков будут убраны из приложения. Сама музыка не удаляется.')}
        actions={<React.Fragment><DialogAction>{t('Cancel', 'Отмена')}</DialogAction><DialogAction kind="danger">{t('Delete', 'Удалить')}</DialogAction></React.Fragment>} />
    </Frame>
  );
}

// Player's existing queue sheet (track list of the active queue) gains the way into the queue list.
function PhonePlayerQueue() {
  const t = useT();
  return (
    <Frame kind="phone" tint="#8A5A22" glowY="18%">
      <Scrim opacity={0.45} />
      <Sheet height={700}>
        <QueueSheetHeader title={t('With family', 'С семьёй')} subtitle={t('Queue · 12 of 48', 'Очередь · 12 из 48')}
          trailing={<GlassPill icon="stacks" height={44}>{t('Queues', 'Очереди')}</GlassPill>} />
        <div style={{ padding: '4px 20px' }}>
          <SectionCaps style={{ margin: '6px 0 8px' }}>{t('Now playing', 'Сейчас играет')}</SectionCaps>
          <div style={{ borderRadius: 18, padding: '0 12px', margin: '0 -8px', background: `linear-gradient(180deg, ${HF.anodLight}, ${HF.anodDark})`,
            boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.20), 0 0 0 1.5px rgba(201,206,216,0.50)' }}>
            <TrackRow title="Wish You Were Here" artist="Pink Floyd" hue="amber" trailing={<Icon name="graphic_eq" size={24} />} />
          </div>
          <SectionCaps style={{ margin: '18px 0 4px' }}
            trailing={<div style={{ display: 'flex', gap: 14 }}><Icon name="shuffle" size={22} color={HF.text2} /><Icon name="repeat" size={22} color={HF.text2} /></div>}>{t('Next', 'Далее')}</SectionCaps>
          <TrackRow title="Shine On You Crazy Diamond" artist="Pink Floyd" hue="amber" trailing={<GlassCircle icon="close" size={36} />} />
          <TrackRow title="Дороги" artist="Мельница" hue="green" trailing={<GlassCircle icon="close" size={36} />} />
          <TrackRow title={t('Yandex track — signed out', 'Трек Яндекса — нет входа')} artist={t('Unavailable, will be skipped', 'Недоступен, будет пропущен')} hue="gray" provider="yandex" unavailable trailing={<GlassCircle icon="close" size={36} />} />
          <TrackRow title="Wish You Were Here" artist={t('Pink Floyd · again (duplicates allowed)', 'Pink Floyd · ещё раз (дубли разрешены)')} hue="amber" trailing={<GlassCircle icon="close" size={36} />} />
          <TrackRow title="Comfortably Numb" artist="Pink Floyd" hue="violet" trailing={<GlassCircle icon="close" size={36} />} />
        </div>
      </Sheet>
    </Frame>
  );
}

// Track menu: new queue actions (Phase 1).
function PhoneTrackMenu() {
  const t = useT();
  return (
    <Frame kind="phone">
      <Scrim opacity={0.2} />
      <Sheet height={520}>
        <div style={{ padding: '18px 24px 0' }}>
          <Txt t="dialogTitle">Comfortably Numb</Txt>
          <Txt t="caption" color={HF.text2} style={{ marginBottom: 10 }}>Pink Floyd</Txt>
          <MenuRow icon="skip_next">{t('Play next', 'Играть следующим')}</MenuRow>
          <MenuRow icon="playlist_play">{t('Add to end of queue', 'Добавить в конец очереди')}</MenuRow>
          <MenuRow icon="stacks">{t('Add to queue…', 'Добавить в очередь…')}</MenuRow>
          <MenuRow icon="sensors">{t('Start radio', 'Включить радио')}</MenuRow>
          <MenuRow icon="playlist_add">{t('Add to playlist', 'Добавить в плейлист')}</MenuRow>
          <MenuRow icon="favorite">{t('Like', 'Добавить в «Мне нравится»')}</MenuRow>
        </div>
      </Sheet>
    </Frame>
  );
}
function PhoneAddToQueue() {
  const t = useT(); const queues = useQueues().filter((q) => q.kind === 'static');
  return (
    <Frame kind="phone">
      <Scrim opacity={0.2} />
      <Sheet height={520}>
        <QueueSheetHeader title={t('Add to queue', 'Добавить в очередь')} subtitle="Comfortably Numb · Pink Floyd" />
        <div style={{ padding: '0 8px', display: 'flex', flexDirection: 'column', gap: 4 }}>
          {queues.map((q) => <QueueRow key={q.id} q={{ ...q, active: false }} />)}
        </div>
        <div style={{ position: 'absolute', left: 20, right: 20, bottom: 24 }}><GlassPill icon="add">{t('New queue', 'Новая очередь')}</GlassPill></div>
      </Sheet>
    </Frame>
  );
}

// ── Head unit 1920×720 ───────────────────────────────────────────────────────
function CarTile({ icon, label, active }) {
  return (
    <div style={{ width: 132, height: 96, borderRadius: 22, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 6,
      background: active ? 'rgba(255,255,255,0.16)' : 'rgba(28,30,38,0.72)', border: `1px solid ${active ? 'rgba(255,255,255,0.35)' : HF.glassBorder}` }}>
      <Icon name={icon} size={30} /><div style={{ fontSize: 17, fontWeight: 700 }}>{label}</div>
    </div>
  );
}
function CarPill({ icon, children, trailing, active }) {
  return (
    <div style={{ height: 60, borderRadius: 30, padding: '0 22px', display: 'flex', alignItems: 'center', gap: 12, maxWidth: 380,
      background: active ? `linear-gradient(180deg, ${HF.anodLight}, ${HF.anodDark})` : HF.glassFill,
      boxShadow: active ? 'inset 0 1px 0 rgba(255,255,255,0.20), 0 0 0 1.5px rgba(201,206,216,0.50)' : 'none',
      border: active ? 'none' : `1px solid ${HF.glassBorder}`, fontSize: 20, fontWeight: 700 }}>
      <Icon name={icon} size={28} /><div className="hf-ellipsis">{children}</div>{trailing}
    </div>
  );
}

// Car tiles in priority order (D33): the phone's tabs plus the most useful Library rows. As many as fit are
// shown and the row scrolls sideways for the rest. A tile exists only while a signed-in source supports it.
// There is no "Offline" tile: downloaded music is offered by the weak-signal pill when it is needed.
function useCarTiles() {
  const t = useT();
  return [
    { id: 'folders', icon: 'folder', label: t('Folders', 'Папки') },
    { id: 'wave', icon: 'waves', label: t('My Wave', 'Моя волна') },
    { id: 'liked', icon: 'favorite', label: t('Liked', 'Любимое') },
    { id: 'mixes', icon: 'auto_awesome', label: t('Mixes', 'Миксы') },
    { id: 'radio', icon: 'radio', label: t('Radio', 'Радио') },
  ];
}
function CarTileRow({ count = 5, active = 'folders', style }) {
  const tiles = useCarTiles();
  return (
    <div style={{ display: 'flex', gap: 14, ...style }}>
      {tiles.slice(0, count).map((x) => <CarTile key={x.id} icon={x.icon} label={x.label} active={x.id === active} />)}
    </div>
  );
}

// Car mode. `w` = pane width (1920 full screen, 1280 wide split pane). `far` replaces the far column.
// `weak` = the connection is weak or lost: the radio pill gives way to "play downloaded".
function CarHome({ dim, w = 1920, far, weak, activeTile }) {
  const t = useT();
  const wide = w >= 1900, panel = wide ? 760 : 700;
  return (
    <React.Fragment>
      {/* driver panel */}
      <div style={{ position: 'absolute', left: 48, top: 30, width: panel }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <GlassCircle icon="arrow_back" size={60} />
          <CarPill icon="stacks" active trailing={<Icon name="expand_more" size={28} color={HF.text2} />}>{t('With family', 'С семьёй')}</CarPill>
          {weak
            ? <CarPill icon="wifi_off" trailing={<Icon name="play_arrow" size={28} />}>{t('Weak signal · downloaded', 'Слабая связь · скачанное')}</CarPill>
            : <CarPill icon="sensors">{t('Radio from track', 'Радио по треку')}</CarPill>}
          {/* Search is one tap away; it opens the normal landscape search (car mode itself never needs typing). */}
          <GlassCircle icon="search" size={60} />
        </div>
        <div style={{ ...HFType.sectionCaps, fontSize: 15, color: HF.text2, marginTop: 30 }}>{t('Queue · 12 of 48', 'Очередь · 12 из 48')}</div>
        <div className="hf-ellipsis" style={{ fontSize: 56, lineHeight: '66px', fontWeight: 800 }}>Wish You Were Here</div>
        <div style={{ fontSize: 30, color: HF.text2 }}>Pink Floyd</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: wide ? 26 : 18, marginTop: 26 }}>
          <GlassCircle icon="heart_broken" size={76} iconSize={34} />
          <Knob icon="skip_previous" size={100} />
          <SpectrumRing size={236} knob={168} />
          <Knob icon="skip_next" size={100} />
          <GlassCircle icon="favorite" size={76} iconSize={34} color={HF.love} />
        </div>
        <Slider value={0.3} style={{ marginTop: 18, width: panel - 60 }} />
      </div>
      <CarTileRow active={activeTile} style={{ position: 'absolute', left: 48, bottom: 28 }} />
      {/* far column */}
      <div style={{ position: 'absolute', right: wide ? 56 : 40, top: 26, textAlign: 'right' }}>
        <div style={{ fontSize: 46, fontWeight: 800, lineHeight: '50px' }}>10:30</div>
        <div style={{ ...HFType.sectionCaps, fontSize: 13, color: HF.text2 }}>{t('Wed · 21 October', 'Среда · 21 октября')}</div>
      </div>
      {far || (
        <React.Fragment>
          <div style={{ position: 'absolute', left: wide ? 980 : 860, top: wide ? 96 : 120 }}>
            <Cover size={wide ? 320 : 300} hue="amber" radius={36} style={{ boxShadow: '0 0 90px rgba(224,160,80,0.45)' }} />
          </div>
          {wide && <CarNext style={{ position: 'absolute', left: 1360, top: 110, width: 500 }} />}
          {!wide && <CarNext rows={2} compact style={{ position: 'absolute', left: 860, top: 452, width: 380 }} />}
        </React.Fragment>
      )}
      {dim && <Scrim opacity={0.6} />}
    </React.Fragment>
  );
}
const CAR_NEXT = [['Shine On You Crazy Diamond', 'Pink Floyd', 'amber'], ['Дороги', 'Мельница', 'green'], ['Comfortably Numb', 'Pink Floyd', 'violet']];
function CarNext({ rows = 3, compact, style }) {
  const t = useT();
  return (
    <div style={style}>
      <div style={{ ...HFType.sectionCaps, fontSize: 15, color: HF.text2, marginBottom: compact ? 6 : 12 }}>{t('Next', 'Далее')}</div>
      {CAR_NEXT.slice(0, rows).map(([a, b, hue]) => (
        <div key={a} style={{ display: 'flex', alignItems: 'center', gap: 16, height: compact ? 76 : 92 }}>
          <Cover size={compact ? 60 : 72} hue={hue} radius={16} />
          <div style={{ minWidth: 0 }}>
            <div className="hf-ellipsis" style={{ fontSize: compact ? 21 : 24, fontWeight: 700 }}>{a}</div>
            <div style={{ fontSize: compact ? 16 : 18, color: HF.text2 }}>{b}</div>
          </div>
        </div>
      ))}
    </div>
  );
}

function CarQueuePanel() {
  const t = useT(); const queues = useQueues();
  return (
    <Frame kind="head" tint="#8A5A22" glowY="40%">
      <CarHome dim />
      <SidePanel width={720} side="left">
        <QueueSheetHeader big title={t('Queues', 'Очереди')} trailing={<GlassCircle icon="add" size={60} />} />
        <div style={{ padding: '0 14px', display: 'flex', flexDirection: 'column', gap: 6 }}>
          {queues.map((q) => <QueueRow key={q.id} q={q} big />)}
        </div>
      </SidePanel>
    </Frame>
  );
}

Object.assign(window, { useCarTiles, CarTileRow, CarNext, CarPill, CarTile, QueueSheetHeader, PhoneHome, PhoneQueueSheet, PhoneQueueMenu, PhoneQueueNew, PhoneQueueDelete, PhonePlayerQueue, PhoneTrackMenu, PhoneAddToQueue, CarHome, CarQueuePanel, SpectrumRing, QueueRow, useQueues });
