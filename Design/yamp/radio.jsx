// radio.jsx — Phase 2: radio queues (docs/phase-2-radio.md; D35′, D51–D54). "Start radio" opens the queue
// switcher in picker mode: Keep switch, the existing radios (tap = re-seed in place), "New radio". As built —
// compare with the goldens radio_picker / radio_switcher / head_radio_picker.
// Exposes radioSections(): wired into YAMP.html's buildSections() after the queues sections.

const RD_ICON = { static: 'queue_music', wave: 'waves', radio: 'sensors', mix: 'auto_awesome' };

function useRadios() {
  const t = useT();
  return [
    { id: 'r-time', kind: 'radio', name: t('Radio: Time', 'Радио: Time'), track: 'Echoes', artist: 'Pink Floyd', hue: 'violet', progress: 0.55, keep: true },
    { id: 'r-mel', kind: 'radio', name: t('Radio: Мельница', 'Радио: Мельница'), track: 'Дороги', artist: 'Мельница', hue: 'green', progress: 0.2 },
  ];
}

// A queue row as the switcher draws it; a radio shows ∞, and with Keep on the History marker beside it
// (inactive rows only — the active row's slot is the playing indicator).
function RdRow({ q, big }) {
  const h = big ? 100 : 78, cover = big ? 68 : 52;
  return (
    <div style={{ height: h, borderRadius: big ? 24 : 18, padding: big ? '0 20px 0 16px' : '0 14px 0 12px', display: 'flex', alignItems: 'center', gap: big ? 18 : 12,
      background: q.active ? `linear-gradient(180deg, ${HF.anodLight} 0%, ${HF.anodDark} 100%)` : 'transparent',
      boxShadow: q.active ? 'inset 0 1px 0 rgba(255,255,255,0.20), 0 0 0 1.5px rgba(201,206,216,0.50)' : 'none' }}>
      <div style={{ position: 'relative' }}>
        <Cover size={cover} hue={q.hue} radius={big ? 14 : 10} />
        <div style={{ position: 'absolute', right: -6, bottom: -6, width: big ? 28 : 22, height: big ? 28 : 22, borderRadius: 14, background: HF.sheet,
          border: `1px solid ${HF.glassBorder}`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name={RD_ICON[q.kind]} size={big ? 17 : 14} color={HF.text2} />
        </div>
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="hf-ellipsis" style={{ fontSize: big ? 24 : 17, lineHeight: big ? '30px' : '22px', fontWeight: 700 }}>{q.name}</div>
        <div className="hf-ellipsis" style={{ fontSize: big ? 17 : 13, lineHeight: big ? '22px' : '17px', color: HF.text2 }}>{q.track} · {q.artist}</div>
        <ProgressBar value={q.progress} height={big ? 4 : 3} color={q.active ? '#fff' : 'rgba(255,255,255,0.55)'} style={{ marginTop: big ? 10 : 7 }} />
      </div>
      <div style={{ minWidth: big ? 84 : 58, display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: big ? 10 : 8 }}>
        {q.active
          ? <Icon name="graphic_eq" size={big ? 34 : 24} />
          : <>
              {q.keep && <Icon name="history" size={big ? 18 : 14} color={HF.text3} />}
              <div style={{ fontSize: big ? 17 : 13, color: HF.text3 }}>{q.total ? `${q.pos} / ${q.total}` : '∞'}</div>
            </>}
      </div>
    </div>
  );
}

// The picker's Keep switch (D52): a whole-row target, off by default, big in the car.
function RdKeepRow({ on, big }) {
  const t = useT();
  return (
    <div style={{ height: big ? 88 : 64, borderRadius: big ? 24 : 18, padding: big ? '0 20px' : '0 14px 0 16px', display: 'flex', alignItems: 'center', gap: big ? 18 : 12 }}>
      <Icon name="history" size={big ? 32 : 24} color={HF.text2} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: big ? 22 : 16, fontWeight: 700 }}>{t('Keep history', 'Хранить историю')}</div>
        <div style={{ fontSize: big ? 16 : 12, lineHeight: big ? '20px' : '16px', color: HF.text2 }}>
          {t('Every played track stays in the radio; off — only the last few', 'Все прослушанные треки остаются в радио; выкл. — только несколько последних')}
        </div>
      </div>
      <Switch on={on} />
    </div>
  );
}

function PhoneRadioPicker() {
  const t = useT(); const radios = useRadios();
  return (
    <Frame kind="phone">
      <PhoneHome dim />
      <Sheet height={640}>
        <QueueSheetHeader title={t('Start radio', 'Запустить радио')} subtitle="Comfortably Numb" />
        <div style={{ padding: '0 8px', display: 'flex', flexDirection: 'column', gap: 4 }}>
          <RdKeepRow />
          {radios.map((q) => <RdRow key={q.id} q={q} />)}
        </div>
        <div style={{ position: 'absolute', left: 20, right: 20, bottom: 24 }}><GlassPill icon="add">{t('New radio', 'Новое радио')}</GlassPill></div>
      </Sheet>
    </Frame>
  );
}

function PhoneRadioSwitcher() {
  const t = useT(); const radios = useRadios();
  const queues = [
    { ...radios[0], active: true },
    { id: 'family', kind: 'static', name: t('With family', 'С семьёй'), track: 'Wish You Were Here', artist: 'Pink Floyd', hue: 'amber', pos: 12, total: 48, progress: 0.42 },
    { id: 'wave', kind: 'wave', name: t('My Wave', 'Моя волна'), track: t('First song', 'Первая песня'), artist: t('The Band', 'Группа'), hue: 'blue', progress: 0.3 },
    { ...radios[1] },
    { id: 'r-pf', kind: 'radio', name: t('Radio: Pink Floyd', 'Радио: Pink Floyd'), track: 'Time', artist: 'Pink Floyd', hue: 'rose', progress: 0.1, keep: true },
  ];
  return (
    <Frame kind="phone">
      <PhoneHome dim />
      <Sheet height={640}>
        <QueueSheetHeader title={t('Queues', 'Очереди')} subtitle={t('Tap to switch · hold for more', 'Нажмите, чтобы переключить · удерживайте для действий')} />
        <div style={{ padding: '0 8px', display: 'flex', flexDirection: 'column', gap: 4 }}>
          {queues.map((q) => <RdRow key={q.id} q={q} />)}
        </div>
        <div style={{ position: 'absolute', left: 20, right: 20, bottom: 24 }}><GlassPill icon="add">{t('New queue', 'Новая очередь')}</GlassPill></div>
      </Sheet>
    </Frame>
  );
}

function CarRadioPicker() {
  const t = useT(); const radios = useRadios();
  return (
    <Frame kind="head" tint="#8A5A22" glowY="40%">
      <CarHome dim />
      <SidePanel width={720} side="left">
        <QueueSheetHeader big title={t('Start radio', 'Запустить радио')} subtitle="Comfortably Numb" trailing={<GlassCircle icon="add" size={60} />} />
        <div style={{ padding: '0 14px', display: 'flex', flexDirection: 'column', gap: 6 }}>
          <RdKeepRow big />
          {radios.map((q) => <RdRow key={q.id} q={q} big />)}
        </div>
      </SidePanel>
    </Frame>
  );
}

function radioSections() {
  const P = FRAMES.phone, H = FRAMES.head;
  return [
    { id: 'radio-phone', title: '5c · Radio — phone (Phase 2)',
      subtitle: 'As many radios as you start (D35′). "Start radio" (track menu, player, album / artist ⋮ — off My Wave, D54) opens the switcher as a picker: tap a radio = re-seed it in place, no confirmation; "New radio" = a new queue; your own queues are never a target (D51). Keep (D52, off by default) = retain history; off drops the played head at the next refill. Hold a radio for Keep / Delete.',
      boards: [
        { id: 'rd-picker', label: 'Start radio · Keep switch, radios to re-seed, New radio', w: P.w, h: P.h, el: <PhoneRadioPicker /> },
        { id: 'rd-switcher', label: 'Queues · radios show ∞, Keep marker on inactive rows', w: P.w, h: P.h, el: <PhoneRadioSwitcher /> },
      ] },
    { id: 'radio-car', title: '5d · Radio — head unit 1920×720 (Phase 2)',
      subtitle: 'The same picker in the side panel: big rows, the Keep switch a big row too, "+" in the header = New radio. The car header pill reads "Start radio" on every queue but My Wave (D54).',
      boards: [
        { id: 'rdc-picker', label: 'Car mode · Start radio panel', w: H.w, h: H.h, el: <CarRadioPicker /> },
      ] },
  ];
}

Object.assign(window, { radioSections });
