// buffering.jsx — Phase 3b: the next track's state and the buffered part of the progress bar (D80, D81).
// Components only (no sections of its own): each variant is registered next to its existing board —
// phone in section 4 (beside the player), car in section 5 (beside car mode), split panes in section 8.
//
// Next is never disabled. Three states:
//   ready   — the next track is loaded: the normal knob; a tap switches at once.
//   loading — a thin ring spins around the knob; a tap waits for the load, the current track plays on (D67).
//   failed  — the knob is dimmed with a small retry badge; a tap retries (fresh link) and stays load-first;
//             the load also retries by itself when the connection comes back.
// The progress bar shows the buffered part as a lighter fill ahead of the played part.

const NB_ARC_DEG = 110; // visible arc of the loading ring

// A ring arc drawn around a circle of `size` (static in the mock; it spins in the app).
function NbRing({ size, gap = 6, width = 4, color = HF.accent }) {
  const box = size + (gap + width) * 2, r = size / 2 + gap + width / 2, c = box / 2;
  const a0 = -90 * Math.PI / 180, a1 = (NB_ARC_DEG - 90) * Math.PI / 180;
  const d = `M ${c + r * Math.cos(a0)} ${c + r * Math.sin(a0)} A ${r} ${r} 0 0 1 ${c + r * Math.cos(a1)} ${c + r * Math.sin(a1)}`;
  return (
    <svg width={box} height={box} style={{ position: 'absolute', left: -(gap + width), top: -(gap + width), pointerEvents: 'none' }}>
      <circle cx={c} cy={c} r={r} fill="none" stroke="rgba(255,255,255,0.12)" strokeWidth={width} />
      <path d={d} fill="none" stroke={color} strokeWidth={width} strokeLinecap="round" />
    </svg>
  );
}

// The small retry badge of the failed state.
function NbRetryBadge({ size }) {
  const b = Math.max(22, Math.round(size * 0.34));
  return (
    <div style={{ position: 'absolute', right: -b * 0.12, bottom: -b * 0.12, width: b, height: b, borderRadius: b / 2,
      background: HF.warning, border: `2px solid ${HF.ground}`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <Icon name="refresh" size={Math.round(b * 0.66)} color="#1A1200" weight={700} />
    </div>
  );
}

// The Next knob in one of the three states.
function NextKnob({ state = 'ready', size = 72 }) {
  return (
    <div style={{ position: 'relative', width: size, height: size, flex: '0 0 auto' }}>
      <Knob icon="skip_next" size={size} style={state === 'failed' ? { opacity: 0.45 } : undefined} />
      {state === 'loading' && <NbRing size={size} gap={Math.max(4, size * 0.06)} width={Math.max(3, size * 0.045)} />}
      {state === 'failed' && <NbRetryBadge size={size} />}
    </div>
  );
}

// The small Next icon of the mini player in one of the three states.
function NextIcon({ state = 'ready', size = 26 }) {
  return (
    <div style={{ position: 'relative', width: size + 12, height: size + 12, display: 'flex', alignItems: 'center', justifyContent: 'center', flex: '0 0 auto' }}>
      <Icon name="skip_next" size={size} color={state === 'failed' ? HF.text3 : '#fff'} />
      {state === 'loading' && <NbRing size={size + 4} gap={0} width={2.5} />}
      {state === 'failed' && <NbRetryBadge size={size + 2} />}
    </div>
  );
}

// The next track's artwork (car mode "Next" list, first row) in one of the three states.
function NextCover({ state = 'ready', size = 72, hue, radius = 16 }) {
  return (
    <div style={{ position: 'relative', width: size, height: size, flex: '0 0 auto' }}>
      <Cover size={size} hue={hue} radius={radius} style={state === 'failed' ? { opacity: 0.4 } : undefined} />
      {state === 'loading' && (
        <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <div style={{ position: 'relative', width: size * 0.5, height: size * 0.5 }}><NbRing size={size * 0.5} gap={0} width={3.5} /></div>
        </div>
      )}
      {state === 'failed' && (
        <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name="refresh" size={Math.round(size * 0.46)} color={HF.warning} weight={700} />
        </div>
      )}
    </div>
  );
}

// Progress bar with the buffered part: played (white) → buffered (light) → rest (track).
function BufferBar({ played = 0.3, buffered = 0.55, height = 4, style }) {
  return (
    <div style={{ position: 'relative', height, borderRadius: height / 2, background: 'rgba(255,255,255,0.12)', overflow: 'hidden', ...style }}>
      <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: `${buffered * 100}%`, background: 'rgba(255,255,255,0.30)' }} />
      <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: `${played * 100}%`, background: '#fff', borderRadius: height / 2 }} />
    </div>
  );
}

// The car slider with the buffered part (same thumb as Slider).
function BufferSlider({ played = 0.3, buffered = 0.55, style }) {
  return (
    <div style={{ position: 'relative', height: 24, ...style }}>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 10, height: 4, borderRadius: 2, background: 'rgba(255,255,255,0.12)' }} />
      <div style={{ position: 'absolute', left: 0, width: `${buffered * 100}%`, top: 10, height: 4, borderRadius: 2, background: 'rgba(255,255,255,0.30)' }} />
      <div style={{ position: 'absolute', left: 0, width: `${played * 100}%`, top: 10, height: 4, borderRadius: 2,
        background: 'linear-gradient(90deg, rgba(255,255,255,0.55), #fff)' }} />
      <div style={{ position: 'absolute', left: `calc(${played * 100}% - 10px)`, top: 2, width: 20, height: 20, borderRadius: 10,
        background: `radial-gradient(circle at 50% 30%, ${HF.steelLight}, ${HF.steelDark})`, boxShadow: '0 0 0 1.5px rgba(201,206,216,0.7), 0 2px 6px rgba(0,0,0,0.6)' }} />
    </div>
  );
}

// What the state means, as the app says it (tooltip / accessibility label).
function useNextLabel() {
  const t = useT();
  return {
    ready: t('Next track ready', 'Следующий трек готов'),
    loading: t('Next track loading', 'Следующий трек загружается'),
    failed: t("Couldn't load — tap to retry", 'Не загрузился — нажмите, чтобы повторить'),
  };
}
// A small caption chip that explains the state on the board (the app shows the same text as a tooltip / a11y label).
function NbCaption({ state, style }) {
  const label = useNextLabel();
  const color = state === 'failed' ? HF.warning : state === 'loading' ? HF.accent : HF.success;
  return (
    <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8, height: 34, padding: '0 14px', borderRadius: 17,
      background: 'rgba(15,17,24,0.92)', border: `1px solid ${HF.glassBorder}`, ...style }}>
      <div style={{ width: 8, height: 8, borderRadius: 4, background: color }} />
      <span style={{ fontSize: 14, fontWeight: 600 }}>{label[state]}</span>
    </div>
  );
}

function nbTime(f, total = 312) { const s = Math.round(f * total); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; }

// Phone full player (as built: cover, title, progress, transport) with the Next state and the buffered part.
// `start` = the first seconds of a track: played 0, buffered growing.
function PhonePlayerNext({ state = 'ready', start }) {
  const t = useT();
  const played = start ? 0 : 0.34, buffered = start ? 0.06 : state === 'ready' ? 0.62 : 0.48;
  return (
    <Frame kind="phone" tint="#8A5A22" glowY="22%">
      <div style={{ position: 'absolute', left: 16, right: 16, top: 18, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <GlassCircle icon="expand_more" size={44} />
        <div style={{ textAlign: 'center' }}>
          <div style={{ ...HFType.sectionCaps, fontSize: 12, color: HF.text2 }}>{t('With family', 'С семьёй')}</div>
          <div style={{ fontSize: 13, color: HF.text3 }}>{t('Queue · 12 of 48', 'Очередь · 12 из 48')}</div>
        </div>
        <GlassCircle icon="stacks" size={44} />
      </div>
      <div style={{ position: 'absolute', left: 36, top: 96 }}>
        <Cover size={340} hue="amber" radius={28} style={{ boxShadow: '0 0 80px rgba(224,160,80,0.38)' }} />
      </div>
      <div style={{ position: 'absolute', left: 24, right: 24, top: 462, display: 'flex', alignItems: 'center', gap: 12 }}>
        <GlassCircle icon="heart_broken" size={44} />
        <div style={{ flex: 1, minWidth: 0, textAlign: 'center' }}>
          <div className="hf-ellipsis" style={{ fontSize: 24, fontWeight: 800 }}>Wish You Were Here</div>
          <Txt t="body" color={HF.text2}>Pink Floyd</Txt>
        </div>
        <GlassCircle icon="favorite" size={44} color={HF.love} />
      </div>
      <div style={{ position: 'absolute', left: 24, right: 24, top: 548 }}>
        <BufferBar played={played} buffered={buffered} height={5} />
        <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 8, fontSize: 13, color: HF.text2 }}>
          <span>{nbTime(played)}</span><span>5:12</span>
        </div>
      </div>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 612, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 30 }}>
        <Knob icon="skip_previous" size={64} />
        <Knob icon="pause" size={92} />
        <NextKnob state={state} size={64} />
      </div>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 730, display: 'flex', justifyContent: 'center' }}>
        {start
          ? <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8, height: 34, padding: '0 14px', borderRadius: 17,
              background: 'rgba(15,17,24,0.92)', border: `1px solid ${HF.glassBorder}`, fontSize: 14, fontWeight: 600 }}>
              <div style={{ width: 8, height: 8, borderRadius: 4, background: HF.accent }} />
              {t('Track start · the buffered part grows ahead of 0:00', 'Начало трека · загруженная часть растёт перед 0:00')}
            </div>
          : <NbCaption state={state} />}
      </div>
      <div style={{ position: 'absolute', left: 20, right: 20, bottom: 30, display: 'flex', justifyContent: 'space-between' }}>
        <GlassCircle icon="shuffle" size={44} /><GlassCircle icon="sensors" size={44} /><GlassCircle icon="repeat" size={44} />
      </div>
    </Frame>
  );
}

// Mini player above the tab bar: the three Next states stacked, plus the buffered line.
function PhoneMiniNext() {
  const t = useT(); const label = useNextLabel();
  const Row = ({ state, buffered }) => (
    <div style={{ marginBottom: 22 }}>
      <div style={{ position: 'relative', height: 62, borderRadius: 31, background: HF.barFill, border: `1px solid ${HF.glassBorder}`,
        display: 'flex', alignItems: 'center', gap: 12, padding: '0 10px 0 8px', overflow: 'hidden' }}>
        <Cover size={46} hue="amber" radius={23} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <Txt t="cardTitle" ellipsis>Wish You Were Here</Txt><Txt t="caption" color={HF.text2} ellipsis>Pink Floyd</Txt>
        </div>
        <Knob icon="pause" size={42} /><NextIcon state={state} />
        <div style={{ position: 'absolute', left: 31, right: 31, bottom: 0, height: 2 }}><BufferBar played={0.34} buffered={buffered} height={2} /></div>
      </div>
      <div style={{ fontSize: 13, color: HF.text2, margin: '8px 0 0 14px' }}>{label[state]}</div>
    </div>
  );
  return (
    <Frame kind="phone" tint="#2C4FA8" glowY="20%">
      <div style={{ position: 'absolute', left: 20, right: 20, top: 26 }}>
        <LargeTitle>{t('Mini player', 'Мини-плеер')}</LargeTitle>
        <Txt t="body" color={HF.text2} style={{ marginTop: 6 }}>{t('Next is never disabled; the line under it shows the buffered part.', 'Далее никогда не выключается; линия снизу показывает загруженную часть.')}</Txt>
      </div>
      <div style={{ position: 'absolute', left: 16, right: 16, top: 180 }}>
        <Row state="ready" buffered={0.62} />
        <Row state="loading" buffered={0.48} />
        <Row state="failed" buffered={0.48} />
      </div>
      <PhoneChrome active="home" />
    </Frame>
  );
}

// Car mode (full screen, wide and slim split panes) with a Next state.
function CarNextState({ state, start }) {
  return (
    <Frame kind="head" tint="#8A5A22" glowY="40%">
      <CarHome nextState={state} played={start ? 0 : 0.3} buffered={start ? 0.05 : state === 'ready' ? 0.6 : 0.46} />
      {!start && <NbCaption state={state} style={{ position: 'absolute', left: 48, bottom: 26, transform: 'scale(1.25)', transformOrigin: 'left bottom' }} />}
    </Frame>
  );
}
function SplitWideNextState({ state }) {
  return (
    <Frame kind="splitW" tint="#8A5A22" glowY="40%">
      <CarHome w={FRAMES.splitW.w} nextState={state} buffered={0.46} />
      <NbCaption state={state} style={{ position: 'absolute', left: 48, bottom: 22, transform: 'scale(1.15)', transformOrigin: 'left bottom' }} />
    </Frame>
  );
}
function SplitNarrowNextState({ state }) {
  return (
    <Frame kind="splitN" tint="#8A5A22" glowY="30%">
      <NarrowCarBody nextState={state} buffered={0.46} />
      <NbCaption state={state} style={{ position: 'absolute', left: 28, top: 548 }} />
    </Frame>
  );
}

Object.assign(window, { NextKnob, NextIcon, NextCover, BufferBar, BufferSlider, NbCaption, PhonePlayerNext, PhoneMiniNext,
  CarNextState, SplitWideNextState, SplitNarrowNextState });
