// hifi.jsx — YAMP "Hi-Fi" design tokens and primitives for the design canvas.
// Mirrors android/.../presentation/hifi (HiFi.kt, HiFiControls.kt). Values are the app's;
// change them there first, then here. See DESIGN_GUIDE.md.

const HF = {
  ground: '#05060A', accent: '#0A6CFF',
  text: '#FFFFFF', text2: 'rgba(255,255,255,0.66)', text3: 'rgba(255,255,255,0.55)',
  hairline: 'rgba(255,255,255,0.10)', glassFill: 'rgba(255,255,255,0.08)', glassBorder: 'rgba(255,255,255,0.16)',
  barFill: 'rgba(28,30,38,0.72)', scrim: 'rgba(5,6,10,0.90)', sheet: '#10121A', placeholder: '#1C1F28',
  dialog: '#15171F',
  danger: '#FF6B6B', live: '#FF4D4D', success: '#6FD08C', warning: '#FFB547', love: '#FF3B5C',
  onMetal: '#F4F6FA', anodLight: '#2F333E', anodDark: '#16181E',
  steelLight: '#3A4152', steelDark: '#1C2130', steelEdge: '#C9CED8', paper: '#E9ECF2',
  knob: ['#2C303B', '#15171D', '#0A0B0F'],
};

// HiFiType: size / line-height / weight
const HFType = {
  largeTitle: { fontSize: 30, lineHeight: '36px', fontWeight: 700 },
  sectionCaps: { fontSize: 13, lineHeight: '18px', fontWeight: 800, letterSpacing: '0.1em', textTransform: 'uppercase' },
  rowTitle: { fontSize: 17, lineHeight: '22px', fontWeight: 600 },
  cardTitle: { fontSize: 15, lineHeight: '19px', fontWeight: 700 },
  body: { fontSize: 15, lineHeight: '20px', fontWeight: 400 },
  caption: { fontSize: 13, lineHeight: '17px', fontWeight: 400 },
  dialogTitle: { fontSize: 20, lineHeight: '26px', fontWeight: 800 },
};

// Proposed in DESIGN_GUIDE §2 (collected from today's literals).
const HFSpace = { xs: 4, s: 8, m: 12, gutter: 16, list: 20, l: 24, xl: 32 };
const HFShape = { row: 12, cover: 10, dialog: 28, bar: 32, pill: 999 };

if (typeof document !== 'undefined' && !document.getElementById('hf-styles')) {
  const s = document.createElement('style');
  s.id = 'hf-styles';
  s.textContent = [
    '.hf{font-family:Roboto,system-ui,sans-serif;color:#fff;-webkit-font-smoothing:antialiased;box-sizing:border-box}',
    '.hf *{box-sizing:border-box}',
    ".msr{font-family:'Material Symbols Rounded';font-weight:normal;font-style:normal;line-height:1;letter-spacing:normal;",
    "  text-transform:none;display:inline-block;white-space:nowrap;direction:ltr;-webkit-font-feature-settings:'liga';",
    "  font-feature-settings:'liga';-webkit-font-smoothing:antialiased;user-select:none}",
    '.hf-ellipsis{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
  ].join('\n');
  document.head.appendChild(s);
}

// ── Language (ARCHITECTURE K: English default + Russian) ─────────────────────
const LangCtx = React.createContext('en');
function useT() {
  const lang = React.useContext(LangCtx);
  return (en, ru) => (lang === 'ru' && ru != null ? ru : en);
}

// ── Basics ───────────────────────────────────────────────────────────────────
function Icon({ name, size = 24, color = '#fff', fill = 1, weight = 500, style }) {
  return (
    <span className="msr" style={{ fontSize: size, color, width: size, height: size,
      fontVariationSettings: `'FILL' ${fill}, 'wght' ${weight}, 'GRAD' 0, 'opsz' 24`, ...style }}>{name}</span>
  );
}

function Txt({ t = 'body', color = HF.text, children, style, ellipsis }) {
  return <div className={ellipsis ? 'hf-ellipsis' : undefined} style={{ ...HFType[t], color, ...style }}>{children}</div>;
}

// Ground + tint glow taken from the cover + vignette (lite mode look: no blur, no grain).
function Backdrop({ tint = '#2C4FA8', glowY = '28%', children, style }) {
  return (
    <div className="hf" style={{ position: 'absolute', inset: 0, overflow: 'hidden', background: HF.ground, ...style }}>
      <div style={{ position: 'absolute', inset: 0,
        background: `radial-gradient(120% 62% at 50% ${glowY}, ${tint}CC 0%, ${tint}55 38%, transparent 72%)` }} />
      <div style={{ position: 'absolute', inset: 0,
        background: 'linear-gradient(180deg, rgba(5,6,10,0.15) 0%, rgba(5,6,10,0) 30%, rgba(5,6,10,0.72) 78%, #05060A 100%)' }} />
      <div style={{ position: 'absolute', inset: 0 }}>{children}</div>
    </div>
  );
}

// Mock cover: flat tinted gradient (real covers come from the provider).
const COVER_HUES = { blue: ['#5B86DD', '#1E2C7A'], rose: ['#D96C8A', '#5A1E3C'], amber: ['#E0A050', '#6A3A12'],
  green: ['#58B889', '#12452F'], violet: ['#9A7BE0', '#35206E'], teal: ['#4FB3C2', '#12414C'], gray: ['#3A3F4C', '#1C1F28'] };
function Cover({ size = 48, hue = 'gray', radius = HFShape.cover, glyph, style }) {
  const [a, b] = COVER_HUES[hue] || COVER_HUES.gray;
  return (
    <div style={{ width: size, height: size, borderRadius: radius, flex: '0 0 auto',
      background: `linear-gradient(145deg, ${a} 0%, ${b} 100%)`,
      border: '1px solid rgba(255,255,255,0.10)', display: 'flex', alignItems: 'center', justifyContent: 'center', ...style }}>
      {glyph && <Icon name={glyph} size={Math.round(size * 0.46)} color="rgba(255,255,255,0.85)" />}
    </div>
  );
}

// ── Buttons ──────────────────────────────────────────────────────────────────
// Primary: anodized metal with a steel rim.
function MetalPill({ icon, children, height = 52, style }) {
  return (
    <div style={{ height, borderRadius: height / 2, padding: '0 22px', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10,
      background: `linear-gradient(180deg, ${HF.anodLight} 0%, ${HF.anodDark} 100%)`,
      boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.22), 0 0 0 1.5px rgba(201,206,216,0.55), 0 8px 18px rgba(0,0,0,0.45)',
      color: HF.onMetal, fontSize: 16, fontWeight: 700, ...style }}>
      {icon && <Icon name={icon} size={22} color={HF.onMetal} />}{children}
    </div>
  );
}
function GlassPill({ icon, children, height = 52, active, style }) {
  return (
    <div style={{ height, borderRadius: height / 2, padding: height >= 48 ? '0 20px' : '0 14px', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
      background: active ? 'rgba(255,255,255,0.16)' : HF.glassFill, border: `1px solid ${HF.glassBorder}`,
      color: '#fff', fontSize: height >= 48 ? 16 : 14, fontWeight: 700, ...style }}>
      {icon && <Icon name={icon} size={height >= 48 ? 22 : 18} />}{children}
    </div>
  );
}
function GlassCircle({ icon, size = 44, iconSize, color = '#fff', badge, style }) {
  return (
    <div style={{ position: 'relative', width: size, height: size, borderRadius: size / 2, flex: '0 0 auto',
      background: HF.glassFill, border: `1px solid ${HF.glassBorder}`, display: 'flex', alignItems: 'center', justifyContent: 'center', ...style }}>
      <Icon name={icon} size={iconSize || Math.round(size * 0.5)} color={color} />
      {badge != null && (
        <div style={{ position: 'absolute', top: -4, right: -4, minWidth: 20, height: 20, borderRadius: 10, padding: '0 5px',
          background: HF.accent, border: `2px solid ${HF.ground}`, fontSize: 11, fontWeight: 800, lineHeight: '16px', textAlign: 'center' }}>{badge}</div>
      )}
    </div>
  );
}
// Transport knob: dark face, machined rim.
function Knob({ icon, size = 72, iconSize, style }) {
  return (
    <div style={{ width: size, height: size, borderRadius: size / 2, flex: '0 0 auto',
      background: `radial-gradient(circle at 50% 30%, ${HF.knob[0]} 0%, ${HF.knob[1]} 55%, ${HF.knob[2]} 100%)`,
      boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.18), 0 0 0 1.5px rgba(201,206,216,0.55), 0 10px 24px rgba(0,0,0,0.55)',
      display: 'flex', alignItems: 'center', justifyContent: 'center', ...style }}>
      <Icon name={icon} size={iconSize || Math.round(size * 0.42)} />
    </div>
  );
}
function TextAction({ children, color = '#fff', style }) {
  return <div style={{ height: 44, padding: '0 8px', display: 'flex', alignItems: 'center', gap: 4, color, fontSize: 15, fontWeight: 700, ...style }}>{children}</div>;
}

// ── Controls ─────────────────────────────────────────────────────────────────
function FilterPill({ children, active, icon, style }) {
  return (
    <div style={{ height: 36, borderRadius: 18, padding: '0 14px', display: 'inline-flex', alignItems: 'center', gap: 6, flex: '0 0 auto',
      background: active ? '#fff' : HF.glassFill, color: active ? '#0B0C10' : '#fff',
      border: `1px solid ${active ? '#fff' : HF.glassBorder}`, fontSize: 14, fontWeight: 700, ...style }}>
      {icon && <Icon name={icon} size={18} color={active ? '#0B0C10' : '#fff'} />}{children}
    </div>
  );
}
function Segmented({ options, value, style }) {
  return (
    <div style={{ height: 40, borderRadius: 20, padding: 3, display: 'flex', background: 'rgba(0,0,0,0.35)', border: `1px solid ${HF.hairline}`, ...style }}>
      {options.map((o) => (
        <div key={o} style={{ flex: 1, borderRadius: 17, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '0 14px',
          fontSize: 14, fontWeight: 700, color: o === value ? '#fff' : HF.text2,
          background: o === value ? `linear-gradient(180deg, ${HF.anodLight}, ${HF.anodDark})` : 'transparent',
          boxShadow: o === value ? 'inset 0 1px 0 rgba(255,255,255,0.18), 0 0 0 1px rgba(201,206,216,0.35)' : 'none' }}>{o}</div>
      ))}
    </div>
  );
}
function Switch({ on }) {
  return (
    <div style={{ width: 52, height: 32, borderRadius: 16, padding: 3, flex: '0 0 auto',
      background: on ? HF.accent : 'rgba(255,255,255,0.12)', border: `1px solid ${on ? 'rgba(255,255,255,0.25)' : HF.glassBorder}` }}>
      <div style={{ width: 24, height: 24, borderRadius: 12, marginLeft: on ? 20 : 0,
        background: `linear-gradient(180deg, #fff, ${HF.steelEdge})`, boxShadow: '0 2px 6px rgba(0,0,0,0.5)' }} />
    </div>
  );
}
function Slider({ value = 0.4, style }) {
  return (
    <div style={{ position: 'relative', height: 24, ...style }}>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 10, height: 4, borderRadius: 2, background: 'rgba(255,255,255,0.12)' }} />
      <div style={{ position: 'absolute', left: 0, width: `${value * 100}%`, top: 10, height: 4, borderRadius: 2,
        background: 'linear-gradient(90deg, rgba(255,255,255,0.55), #fff)' }} />
      <div style={{ position: 'absolute', left: `calc(${value * 100}% - 10px)`, top: 2, width: 20, height: 20, borderRadius: 10,
        background: `radial-gradient(circle at 50% 30%, ${HF.steelLight}, ${HF.steelDark})`, boxShadow: '0 0 0 1.5px rgba(201,206,216,0.7), 0 2px 6px rgba(0,0,0,0.6)' }} />
    </div>
  );
}
function ProgressBar({ value = 0.3, height = 3, color = '#fff', style }) {
  return (
    <div style={{ height, borderRadius: height / 2, background: 'rgba(255,255,255,0.12)', overflow: 'hidden', ...style }}>
      <div style={{ width: `${value * 100}%`, height: '100%', borderRadius: height / 2, background: color }} />
    </div>
  );
}
function Field({ label, value, placeholder, icon, error, secure, style }) {
  return (
    <div style={style}>
      {label && <Txt t="caption" color={HF.text2} style={{ marginBottom: 6, fontWeight: 600 }}>{label}</Txt>}
      <div style={{ height: 52, borderRadius: 14, padding: '0 14px', display: 'flex', alignItems: 'center', gap: 10,
        background: 'rgba(0,0,0,0.35)', border: `1px solid ${error ? HF.danger : HF.glassBorder}`, boxShadow: 'inset 0 2px 6px rgba(0,0,0,0.45)' }}>
        {icon && <Icon name={icon} size={20} color={HF.text3} />}
        <div className="hf-ellipsis" style={{ flex: 1, fontSize: 16, color: value ? '#fff' : HF.text3, letterSpacing: secure && value ? '0.2em' : 0 }}>
          {value ? (secure ? '••••••••••' : value) : placeholder}
        </div>
      </div>
      {error && <Txt t="caption" color={HF.danger} style={{ marginTop: 6 }}>{error}</Txt>}
    </div>
  );
}

// ── Text & rows ──────────────────────────────────────────────────────────────
function LargeTitle({ children, style }) { return <Txt t="largeTitle" style={style}>{children}</Txt>; }
function SectionCaps({ children, trailing, style }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', ...style }}>
      <Txt t="sectionCaps" color={HF.text2}>{children}</Txt>{trailing}
    </div>
  );
}
// Small letter plaque shown only when a list mixes providers (DESIGN_GUIDE §8).
const PROVIDERS = { yandex: { letter: 'Y', color: '#FFCC00', ink: '#1A1400' }, jellyfin: { letter: 'J', color: '#9B6BE0', ink: '#fff' },
  local: { letter: 'L', color: '#6FD08C', ink: '#062012' }, radio: { letter: 'R', color: '#FF6B6B', ink: '#2A0606' } };
function ProviderBadge({ provider, size = 18, style }) {
  const p = PROVIDERS[provider]; if (!p) return null;
  return (
    <div title={provider} style={{ width: size, height: size, borderRadius: Math.round(size * 0.3), flex: '0 0 auto', background: p.color, color: p.ink,
      fontSize: Math.round(size * 0.62), fontWeight: 800, lineHeight: `${size}px`, textAlign: 'center', ...style }}>{p.letter}</div>
  );
}
function IconPlaque({ icon, size = 40, style }) {
  return (
    <div style={{ width: size, height: size, borderRadius: 12, flex: '0 0 auto', background: HF.glassFill, border: `1px solid ${HF.hairline}`,
      display: 'flex', alignItems: 'center', justifyContent: 'center', ...style }}><Icon name={icon} size={Math.round(size * 0.55)} /></div>
  );
}
// Library-style row: plaque, title, count, chevron, hairline.
function NavRow({ icon, title, count, last, cover, subtitle }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 14, height: subtitle ? 64 : 60 }}>
      {cover || <IconPlaque icon={icon} />}
      <div style={{ flex: 1, minWidth: 0, alignSelf: 'stretch', display: 'flex', alignItems: 'center', gap: 10,
        borderBottom: last ? 'none' : `1px solid ${HF.hairline}` }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <Txt t="rowTitle" ellipsis>{title}</Txt>
          {subtitle && <Txt t="caption" color={HF.text2} ellipsis>{subtitle}</Txt>}
        </div>
        {count != null && <Txt t="body" color={HF.text3}>{count}</Txt>}
        <Icon name="chevron_right" size={22} color={HF.text3} />
      </div>
    </div>
  );
}
function TrackRow({ title, artist, hue, provider, trailing, playing, unavailable, duration, style }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, height: 64, opacity: unavailable ? 0.42 : 1, ...style }}>
      <Cover size={48} hue={hue} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <Txt t="rowTitle" ellipsis color={playing ? '#fff' : HF.text}>{title}</Txt>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 1 }}>
          {provider && <ProviderBadge provider={provider} size={15} />}
          <Txt t="caption" color={HF.text2} ellipsis>{artist}</Txt>
        </div>
      </div>
      {duration && <Txt t="caption" color={HF.text3}>{duration}</Txt>}
      {trailing !== undefined ? trailing : <Icon name="more_vert" size={22} color={HF.text2} />}
    </div>
  );
}

// ── Surfaces ─────────────────────────────────────────────────────────────────
function Scrim({ opacity = 0.62 }) { return <div style={{ position: 'absolute', inset: 0, background: `rgba(5,6,10,${opacity})` }} />; }
// Phone: sheet from the bottom with a grab handle.
function Sheet({ children, height, style }) {
  return (
    <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height, background: HF.sheet,
      borderRadius: '28px 28px 0 0', borderTop: `1px solid ${HF.hairline}`, boxShadow: '0 -18px 50px rgba(0,0,0,0.55)', ...style }}>
      <div style={{ width: 44, height: 5, borderRadius: 3, background: 'rgba(255,255,255,0.55)', margin: '10px auto 0' }} />
      {children}
    </div>
  );
}
// Landscape / car: panel on the driver's side.
function SidePanel({ children, width = 520, side = 'left', style }) {
  return (
    <div style={{ position: 'absolute', top: 16, bottom: 16, [side]: 16, width, background: HF.sheet, borderRadius: 28,
      border: `1px solid ${HF.hairline}`, boxShadow: '0 20px 60px rgba(0,0,0,0.65)', overflow: 'hidden', ...style }}>{children}</div>
  );
}
function Dialog({ title, text, children, actions, width = 320, style }) {
  return (
    <div style={{ position: 'absolute', left: '50%', top: '50%', transform: 'translate(-50%,-50%)', width, background: HF.dialog,
      borderRadius: HFShape.dialog, border: `1px solid ${HF.hairline}`, padding: 24, boxShadow: '0 24px 70px rgba(0,0,0,0.7)', ...style }}>
      <Txt t="dialogTitle">{title}</Txt>
      {text && <Txt t="body" color={HF.text2} style={{ marginTop: 10 }}>{text}</Txt>}
      {children}
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 6, marginTop: 20 }}>{actions}</div>
    </div>
  );
}
function DialogAction({ kind = 'quiet', children }) {
  const color = kind === 'danger' ? HF.danger : kind === 'primary' ? '#fff' : HF.text2;
  return (
    <div style={{ height: 44, padding: '0 16px', borderRadius: 22, display: 'flex', alignItems: 'center', fontSize: 15, fontWeight: 700, color,
      background: kind === 'primary' ? HF.glassFill : 'transparent', border: kind === 'primary' ? `1px solid ${HF.glassBorder}` : '1px solid transparent' }}>{children}</div>
  );
}
function MenuRow({ icon, children, color = '#fff', height = 56 }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 18, height }}>
      <Icon name={icon} size={24} color={color} fill={0} /><div style={{ fontSize: 17, color }}>{children}</div>
    </div>
  );
}

// ── Navigation chrome ────────────────────────────────────────────────────────
function useTabs() {
  const t = useT();
  return [
    { id: 'home', icon: 'waves', label: t('Wave', 'Волна') },
    { id: 'search', icon: 'search', label: t('Search', 'Поиск') },
    { id: 'library', icon: 'library_music', label: t('Library', 'Музыка') },
    { id: 'radio', icon: 'radio', label: t('Radio', 'Радио') },
    { id: 'car', icon: 'directions_car', label: t('Car', 'В авто') },
  ];
}
function TabItem({ tab, active, width }) {
  return (
    <div style={{ width, height: 54, borderRadius: 27, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 2,
      background: active ? 'rgba(255,255,255,0.14)' : 'transparent' }}>
      <Icon name={tab.icon} size={22} color={active ? HF.accent : '#fff'} />
      <div style={{ fontSize: 11, fontWeight: 600, color: active ? '#fff' : HF.text2 }}>{tab.label}</div>
    </div>
  );
}
function MiniPlayer({ title, artist, hue = 'blue', progress = 0.34, queueBadge, style }) {
  return (
    <div style={{ position: 'relative', height: 62, borderRadius: 31, background: HF.barFill, border: `1px solid ${HF.glassBorder}`,
      display: 'flex', alignItems: 'center', gap: 12, padding: '0 10px 0 8px', overflow: 'hidden', ...style }}>
      <Cover size={46} hue={hue} radius={23} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <Txt t="cardTitle" ellipsis>{title}</Txt><Txt t="caption" color={HF.text2} ellipsis>{artist}</Txt>
      </div>
      {queueBadge}
      <Knob icon="pause" size={42} /><Icon name="skip_next" size={26} style={{ margin: '0 6px' }} />
      <div style={{ position: 'absolute', left: 31, right: 31, bottom: 0, height: 2, background: 'rgba(255,255,255,0.10)' }}>
        <div style={{ width: `${progress * 100}%`, height: '100%', background: '#fff' }} />
      </div>
    </div>
  );
}
// Portrait: mini player + floating glass bar.
function PhoneChrome({ active = 'home', title, artist, hue }) {
  const tabs = useTabs();
  return (
    <div style={{ position: 'absolute', left: 16, right: 16, bottom: 16 }}>
      {title && <MiniPlayer title={title} artist={artist} hue={hue} style={{ marginBottom: 8 }} />}
      <div style={{ height: 66, borderRadius: HFShape.bar + 1, background: HF.barFill, border: `1px solid ${HF.glassBorder}`,
        display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 6px' }}>
        {tabs.map((tb) => <TabItem key={tb.id} tab={tb} active={tb.id === active} width={70} />)}
      </div>
    </div>
  );
}
// Landscape: one dock = tabs + mini player.
function Dock({ active = 'home', title, artist, hue, extra, miniWidth }) {
  const tabs = useTabs();
  return (
    <div style={{ position: 'absolute', left: 24, right: 24, bottom: 14, display: 'flex', gap: 12, alignItems: 'center' }}>
      <div style={{ height: 62, borderRadius: 31, background: HF.barFill, border: `1px solid ${HF.glassBorder}`,
        display: 'flex', alignItems: 'center', padding: '0 4px' }}>
        {tabs.map((tb) => <TabItem key={tb.id} tab={tb} active={tb.id === active} width={92} />)}
      </div>
      {extra}
      {title && <MiniPlayer title={title} artist={artist} hue={hue} style={miniWidth ? { width: miniWidth } : { flex: 1 }} />}
    </div>
  );
}

// ── Frames ───────────────────────────────────────────────────────────────────
const FRAMES = { phone: { w: 412, h: 915 }, head: { w: 1920, h: 720 }, split: { w: 960, h: 720 }, car: { w: 1280, h: 720 } };
function Frame({ kind = 'phone', tint, glowY, children }) {
  const f = FRAMES[kind];
  return (
    <div className="hf" style={{ position: 'relative', width: f.w, height: f.h, overflow: 'hidden', background: HF.ground }}>
      <Backdrop tint={tint} glowY={glowY}>{children}</Backdrop>
    </div>
  );
}

Object.assign(window, { HF, HFType, HFSpace, HFShape, LangCtx, useT, Icon, Txt, Backdrop, Cover, MetalPill, GlassPill, GlassCircle, Knob,
  TextAction, FilterPill, Segmented, Switch, Slider, ProgressBar, Field, LargeTitle, SectionCaps, ProviderBadge, PROVIDERS, IconPlaque,
  NavRow, TrackRow, Scrim, Sheet, SidePanel, Dialog, DialogAction, MenuRow, MiniPlayer, PhoneChrome, Dock, Frame, FRAMES, useTabs });
