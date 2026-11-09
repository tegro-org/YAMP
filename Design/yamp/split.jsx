// split.jsx — head unit in split screen (navigator + music). The unit divides 1920×720 as 1 : 2:
// a slim pane (about 640×720) and a wide pane (about 1280×720 = the app's car design grid).
// YAMP can land in either pane, so car mode, the queues panel and folders are shown for both.

function SplitWideCar() { return <Frame kind="splitW" tint="#8A5A22" glowY="40%"><CarHome w={1280} /></Frame>; }

function SplitWideQueues() {
  const t = useT(); const queues = useQueues();
  return (
    <Frame kind="splitW" tint="#8A5A22" glowY="40%">
      <CarHome w={1280} dim />
      <SidePanel width={660} side="left">
        <QueueSheetHeader big title={t('Queues', 'Очереди')} trailing={<GlassCircle icon="add" size={60} />} />
        <div style={{ padding: '0 14px', display: 'flex', flexDirection: 'column', gap: 6 }}>{queues.map((q) => <QueueRow key={q.id} q={q} big />)}</div>
      </SidePanel>
    </Frame>
  );
}

// Slim pane: everything in one column, like the app's portrait car layout.
function NarrowCarBody({ dim }) {
  const t = useT();
  return (
    <React.Fragment>
      <div style={{ position: 'absolute', left: 28, right: 28, top: 24, display: 'flex', alignItems: 'center', gap: 12 }}>
        <GlassCircle icon="arrow_back" size={60} />
        <CarPill icon="stacks" active trailing={<Icon name="expand_more" size={28} color={HF.text2} />}>{t('With family', 'С семьёй')}</CarPill>
        <div style={{ flex: 1 }} />
        <div style={{ fontSize: 34, fontWeight: 800 }}>10:30</div>
      </div>
      <div style={{ position: 'absolute', left: 28, right: 28, top: 108, display: 'flex', gap: 20, alignItems: 'center' }}>
        <Cover size={156} hue="amber" radius={26} style={{ boxShadow: '0 0 60px rgba(224,160,80,0.4)' }} />
        <div style={{ minWidth: 0 }}>
          <div style={{ ...HFType.sectionCaps, fontSize: 14, color: HF.text2 }}>{t('Queue · 12 of 48', 'Очередь · 12 из 48')}</div>
          <div style={{ fontSize: 36, lineHeight: '42px', fontWeight: 800, marginTop: 4 }}>Wish You Were Here</div>
          <div style={{ fontSize: 24, color: HF.text2 }}>Pink Floyd</div>
        </div>
      </div>
      <div style={{ position: 'absolute', left: 28, right: 28, top: 290, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <GlassCircle icon="heart_broken" size={64} iconSize={30} />
        <Knob icon="skip_previous" size={88} />
        <SpectrumRing size={196} knob={140} />
        <Knob icon="skip_next" size={88} />
        <GlassCircle icon="favorite" size={64} iconSize={30} color={HF.love} />
      </div>
      <Slider value={0.3} style={{ position: 'absolute', left: 28, right: 28, top: 506 }} />
      <div style={{ position: 'absolute', left: 28, bottom: 24, display: 'flex', gap: 14 }}>
        <CarTile icon="folder" label={t('Folders', 'Папки')} active />
        <CarTile icon="favorite" label={t('Liked', 'Любимое')} />
        <CarTile icon="waves" label={t('My Wave', 'Моя волна')} />
        <CarTile icon="radio" label={t('Radio', 'Радио')} />
      </div>
      {dim && <Scrim opacity={0.6} />}
    </React.Fragment>
  );
}
function SplitNarrowCar() { return <Frame kind="splitN" tint="#8A5A22" glowY="30%"><NarrowCarBody /></Frame>; }

function SplitNarrowQueues() {
  const t = useT(); const queues = useQueues();
  return (
    <Frame kind="splitN" tint="#8A5A22" glowY="30%">
      <NarrowCarBody dim />
      <SidePanel width={608} side="left">
        <QueueSheetHeader big title={t('Queues', 'Очереди')} trailing={<GlassCircle icon="add" size={60} />} />
        <div style={{ padding: '0 12px', display: 'flex', flexDirection: 'column', gap: 6 }}>{queues.map((q) => <QueueRow key={q.id} q={q} big />)}</div>
      </SidePanel>
    </Frame>
  );
}

function SplitNarrowFolders() {
  const w = useFolderWords();
  return (
    <Frame kind="splitN" tint="#22335F" glowY="20%">
      <div style={{ position: 'absolute', left: 28, right: 28, top: 24, display: 'flex', alignItems: 'center', gap: 14 }}>
        <GlassCircle icon="arrow_back" size={60} />
        <div style={{ fontSize: 36, fontWeight: 800, flex: 1 }}>{w.folders}</div>
        <GlassCircle icon="search" size={60} />
      </div>
      <div style={{ position: 'absolute', left: 28, right: 28, top: 104 }}>
        {ROOT_FOLDERS.slice(0, 6).map(([name, hue, n], i) => (
          <div key={name} style={{ display: 'flex', alignItems: 'center', gap: 16, height: 86, borderBottom: i === 5 ? 'none' : `1px solid ${HF.hairline}` }}>
            <Cover size={64} hue={hue} radius={16} glyph="folder" />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="hf-ellipsis" style={{ fontSize: 23, fontWeight: 700 }}>{name}</div>
              <div style={{ fontSize: 16, color: HF.text2 }}>{w.nFolders(n)}</div>
            </div>
            <Knob icon="play_arrow" size={60} />
          </div>
        ))}
      </div>
      <div style={{ position: 'absolute', left: 28, bottom: 20 }}><NowPlayingCapsule width={584} /></div>
    </Frame>
  );
}

Object.assign(window, { SplitWideCar, SplitWideQueues, SplitNarrowCar, SplitNarrowQueues, SplitNarrowFolders });
