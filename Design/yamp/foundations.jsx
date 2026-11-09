// foundations.jsx — tokens and components sheets (DESIGN_GUIDE §2–§3).

function Sheet2({ w, h, children, pad = 32 }) {
  return (
    <div className="hf" style={{ position: 'relative', width: w, height: h, overflow: 'hidden', background: HF.ground }}>
      <Backdrop tint="#22335F" glowY="0%"><div style={{ position: 'absolute', inset: 0, padding: pad }}>{children}</div></Backdrop>
    </div>
  );
}
function Swatch({ name, value, note, wide }) {
  return (
    <div style={{ width: wide ? 168 : 120 }}>
      <div style={{ height: 56, borderRadius: 12, background: value, border: `1px solid ${HF.glassBorder}` }} />
      <div style={{ fontSize: 13, fontWeight: 700, marginTop: 6 }}>{name}</div>
      <div style={{ fontSize: 11, color: HF.text3, fontFamily: 'ui-monospace, monospace' }}>{note || value}</div>
    </div>
  );
}
function Group({ title, children, style }) {
  return (
    <div style={style}>
      <Txt t="sectionCaps" color={HF.text2} style={{ marginBottom: 12 }}>{title}</Txt>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 14, alignItems: 'flex-end' }}>{children}</div>
    </div>
  );
}

function TokensSheet() {
  return (
    <Sheet2 w={1180} h={860}>
      <LargeTitle>Hi-Fi tokens</LargeTitle>
      <Txt t="caption" color={HF.text2} style={{ marginTop: 4, marginBottom: 22 }}>
        Mirrored from presentation/hifi/HiFi.kt. Dark only. One accent. App-wide tint comes from the current cover.
      </Txt>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '26px 40px' }}>
        <Group title="Base & text">
          <Swatch name="Ground" value={HF.ground} /><Swatch name="Accent" value={HF.accent} />
          <Swatch name="TextPrimary" value="#FFFFFF" /><Swatch name="TextSecondary" value="rgba(255,255,255,0.66)" note="white α0.66" />
          <Swatch name="TextTertiary" value="rgba(255,255,255,0.55)" note="white α0.55" />
        </Group>
        <Group title="Glass & surfaces">
          <Swatch name="GlassFill" value={HF.glassFill} note="white α0.08" /><Swatch name="GlassBorder" value={HF.glassBorder} note="white α0.16" />
          <Swatch name="Hairline" value={HF.hairline} note="white α0.10" /><Swatch name="BarFill" value={HF.barFill} note="#1C1E26 α0.72" />
          <Swatch name="SheetFill" value={HF.sheet} /><Swatch name="Dialog" value={HF.dialog} /><Swatch name="Placeholder" value={HF.placeholder} />
        </Group>
        <Group title="Metal">
          <Swatch name="Anodized" value={`linear-gradient(180deg, ${HF.anodLight}, ${HF.anodDark})`} note="#2F333E → #16181E" />
          <Swatch name="Steel" value={`linear-gradient(180deg, ${HF.steelLight}, ${HF.steelDark})`} note="#3A4152 → #1C2130" />
          <Swatch name="SteelEdge" value={HF.steelEdge} /><Swatch name="OnMetal" value={HF.onMetal} />
          <Swatch name="KnobFace" value={`radial-gradient(circle at 50% 30%, ${HF.knob[0]}, ${HF.knob[1]} 55%, ${HF.knob[2]})`} note="#2C303B…#0A0B0F" />
        </Group>
        <Group title="Status">
          <Swatch name="Danger" value={HF.danger} /><Swatch name="Live" value={HF.live} /><Swatch name="Success" value={HF.success} />
          <Swatch name="Warning" value={HF.warning} /><Swatch name="Love" value={HF.love} />
        </Group>
        <div>
          <Txt t="sectionCaps" color={HF.text2} style={{ marginBottom: 12 }}>Type — HiFiType (Roboto)</Txt>
          {[['largeTitle', 'LargeTitle 30/36 Bold'], ['dialogTitle', 'Dialog title 20 ExtraBold'], ['rowTitle', 'RowTitle 17/22 SemiBold'],
            ['cardTitle', 'CardTitle 15/19 Bold'], ['body', 'Body 15/20'], ['caption', 'Caption 13/17'], ['sectionCaps', 'SectionCaps 13/18 ExtraBold']].map(([k, label]) => (
            <div key={k} style={{ display: 'flex', alignItems: 'baseline', gap: 16, marginBottom: 8 }}>
              <div style={{ width: 300, ...HFType[k] }}>Queues · Очереди</div>
              <div style={{ fontSize: 12, color: HF.text3, fontFamily: 'ui-monospace, monospace' }}>{label}</div>
            </div>
          ))}
        </div>
        <div>
          <Txt t="sectionCaps" color={HF.text2} style={{ marginBottom: 12 }}>Space & shape — to be added as HiFiSpace / HiFiShape</Txt>
          <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', marginBottom: 18 }}>
            {[4, 8, 12, 16, 20, 24, 32].map((n) => (
              <div key={n} style={{ textAlign: 'center' }}>
                <div style={{ width: n, height: n, background: HF.accent, borderRadius: 2, margin: '0 auto 6px' }} />
                <div style={{ fontSize: 11, color: HF.text3 }}>{n}{n === 16 ? ' gutter' : n === 20 ? ' list' : ''}</div>
              </div>
            ))}
          </div>
          <div style={{ display: 'flex', gap: 14, alignItems: 'flex-end' }}>
            {[['cover', 10], ['row', 12], ['dialog', 28], ['bar', 32], ['pill', 999]].map(([n, r]) => (
              <div key={n} style={{ textAlign: 'center' }}>
                <div style={{ width: 76, height: 52, borderRadius: Math.min(r, 26), background: HF.glassFill, border: `1px solid ${HF.glassBorder}` }} />
                <div style={{ fontSize: 11, color: HF.text3, marginTop: 6 }}>{n} {r === 999 ? '∞' : r}</div>
              </div>
            ))}
          </div>
          <Txt t="caption" color={HF.text3} style={{ marginTop: 14 }}>Motion: 120 / 220 / 350 / 700 ms, CubicBezier(0.2, 0, 0, 1). Press = scale 0.97 + haptic, no ripple.</Txt>
        </div>
      </div>
    </Sheet2>
  );
}

function ComponentsSheet() {
  const t = useT();
  return (
    <Sheet2 w={1180} h={980}>
      <LargeTitle>Components</LargeTitle>
      <Txt t="caption" color={HF.text2} style={{ marginTop: 4, marginBottom: 22 }}>
        Reuse before creating. A new primitive goes into presentation/hifi with a screenshot test.
      </Txt>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '26px 40px' }}>
        <Group title="Buttons">
          <MetalPill icon="play_arrow">{t('Play', 'Слушать')}</MetalPill>
          <GlassPill icon="shuffle">{t('Shuffle', 'Перемешать')}</GlassPill>
          <GlassCircle icon="settings" /><GlassCircle icon="stacks" badge={4} />
          <Knob icon="skip_previous" size={56} /><Knob icon="pause" size={76} /><Knob icon="skip_next" size={56} />
          <TextAction>{t('Player', 'Плеер')}<Icon name="chevron_right" size={20} /></TextAction>
        </Group>
        <Group title="Filters & controls">
          <FilterPill active>{t('All', 'Все')}</FilterPill><FilterPill>{t('Tracks', 'Треки')}</FilterPill><FilterPill icon="folder">{t('Folders', 'Папки')}</FilterPill>
          <Segmented options={['MP3', 'AAC', 'FLAC']} value="AAC" style={{ width: 240 }} />
          <Switch on /><Switch />
          <Slider value={0.4} style={{ width: 240 }} />
        </Group>
        <div>
          <Txt t="sectionCaps" color={HF.text2} style={{ marginBottom: 6 }}>Rows</Txt>
          <NavRow icon="favorite" title={t('Liked', 'Мне нравится')} count={128} />
          <NavRow icon="folder" title={t('Folders', 'Папки')} count={34} last />
          <TrackRow title="Wish You Were Here" artist="Pink Floyd" hue="amber" duration="5:34" />
          <TrackRow title="Мельница — Дороги" artist="Мельница" hue="green" provider="jellyfin" duration="4:12" />
          <TrackRow title={t('Not available (signed out)', 'Недоступно (нет входа)')} artist="Yandex Music" hue="gray" provider="yandex" unavailable />
        </div>
        <div>
          <Txt t="sectionCaps" color={HF.text2} style={{ marginBottom: 12 }}>Inputs, badges, progress</Txt>
          <Field label={t('Server address', 'Адрес сервера')} value="https://music.example.org" icon="dns" />
          <Field label={t('Password', 'Пароль')} placeholder={t('Password', 'Пароль')} icon="lock" style={{ marginTop: 12 }} />
          <div style={{ display: 'flex', gap: 10, alignItems: 'center', marginTop: 16 }}>
            <ProviderBadge provider="yandex" size={22} /><ProviderBadge provider="jellyfin" size={22} /><ProviderBadge provider="local" size={22} /><ProviderBadge provider="radio" size={22} />
            <Txt t="caption" color={HF.text3}>{t('Provider badge — only in mixed lists', 'Значок источника — только в смешанных списках')}</Txt>
          </div>
          <ProgressBar value={0.42} style={{ marginTop: 16, width: 320 }} />
        </div>
        <div style={{ position: 'relative', height: 250 }}>
          <Txt t="sectionCaps" color={HF.text2}>Dialog</Txt>
          <Dialog title={t('Delete queue?', 'Удалить очередь?')} text={t('“Solo drive” and its 48 tracks will be removed. Music itself is not deleted.', '«Один за рулём» и 48 треков будут убраны. Сама музыка не удаляется.')}
            actions={<React.Fragment><DialogAction>{t('Cancel', 'Отмена')}</DialogAction><DialogAction kind="danger">{t('Delete', 'Удалить')}</DialogAction></React.Fragment>}
            style={{ left: 170, top: 150 }} />
        </div>
        <div style={{ position: 'relative', height: 250 }}>
          <Txt t="sectionCaps" color={HF.text2} style={{ marginBottom: 12 }}>Chrome</Txt>
          <MiniPlayer title="Time" artist="Pink Floyd" hue="violet" style={{ width: 380 }} />
          <Txt t="caption" color={HF.text3} style={{ margin: '8px 0 14px' }}>MiniPlayer — 62 dp capsule; with the tab bar in portrait, inside the dock in landscape</Txt>
          <div style={{ position: 'relative', height: 70, width: 380 }}>
            <div style={{ position: 'absolute', inset: 0 }}><div style={{ position: 'relative', height: '100%' }}>
              <PhoneChromeInline />
            </div></div>
          </div>
        </div>
      </div>
    </Sheet2>
  );
}
function PhoneChromeInline() {
  const tabs = useTabs();
  return (
    <div style={{ height: 66, borderRadius: 33, background: HF.barFill, border: `1px solid ${HF.glassBorder}`, display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 6px' }}>
      {tabs.map((tb, i) => (
        <div key={tb.id} style={{ width: 70, height: 54, borderRadius: 27, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 2, background: i === 2 ? 'rgba(255,255,255,0.14)' : 'transparent' }}>
          <Icon name={tb.icon} size={22} color={i === 2 ? HF.accent : '#fff'} /><div style={{ fontSize: 11, fontWeight: 600, color: i === 2 ? '#fff' : HF.text2 }}>{tb.label}</div>
        </div>
      ))}
    </div>
  );
}

Object.assign(window, { TokensSheet, ComponentsSheet });
