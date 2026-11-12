// history.jsx — Phase 1b: Library → History (D46, D50). One list of every track that sounded, newest first,
// grouped by day, filtered by queue with the active queue selected by default. Tap = play now, keep my queue.
// Exposes historySections(): wired into YAMP.html's buildSections() next to the queues sections.

function HsChips({ active = 'family' }) {
  const t = useT(); const queues = useQueues();
  const chips = [...queues.filter((q) => q.kind !== 'radio').map((q) => ({ id: q.id, label: q.name })), { id: 'gone', label: t('Road trip', 'Поездка') }, { id: 'all', label: t('All', 'Все') }];
  return (
    <div style={{ display: 'flex', gap: 8, overflow: 'hidden', padding: '0 20px' }}>
      {chips.map((c) => <FilterPill key={c.id} active={c.id === active}>{c.label}</FilterPill>)}
    </div>
  );
}

function HsDay({ children, first }) {
  return <SectionCaps style={{ margin: first ? '14px 0 4px' : '18px 0 4px' }}>{children}</SectionCaps>;
}

function PhoneHistory({ all }) {
  const t = useT();
  // In "All" the second line says where the track played
  const sub = (artist, queue) => (all ? `${artist} · ${queue}` : artist);
  return (
    <Frame kind="phone">
      <div style={{ position: 'absolute', left: 20, right: 20, top: 22, display: 'flex', alignItems: 'center', gap: 14 }}>
        <GlassCircle icon="arrow_back" />
        <div style={{ flex: 1, fontSize: 28, lineHeight: '34px', fontWeight: 700, letterSpacing: '-0.02em' }}>{t('History', 'История')}</div>
        <GlassCircle icon="delete" size={40} />
      </div>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 84 }}>
        <HsChips active={all ? 'all' : 'family'} />
        <div style={{ padding: '0 20px' }}>
          <HsDay first>{t('Today', 'Сегодня')}</HsDay>
          <TrackRow title="Wish You Were Here" artist={sub('Pink Floyd', t('With family', 'С семьёй'))} hue="amber" playing />
          <TrackRow title="Shine On You Crazy Diamond" artist={sub('Pink Floyd', t('With family', 'С семьёй'))} hue="amber" />
          <TrackRow title="Дороги" artist={sub('Мельница', t('With family', 'С семьёй'))} hue="green" />
          <HsDay>{t('Yesterday', 'Вчера')}</HsDay>
          <TrackRow title="Comfortably Numb" artist={sub('Pink Floyd', t('With family', 'С семьёй'))} hue="violet" />
          {all && <TrackRow title="Du hast" artist={sub('Rammstein', t('Solo drive', 'Один за рулём'))} hue="rose" />}
          <HsDay>{t('3 November', '3 ноября')}</HsDay>
          <TrackRow title="Echoes" artist={sub('Pink Floyd', t('With family', 'С семьёй'))} hue="blue" />
          {all && <TrackRow title={t('Chapter 14', 'Глава 14')} artist={sub(t('Roadside Picnic', 'Пикник на обочине'), t('Audiobook', 'Аудиокнига'))} hue="teal" />}
        </div>
      </div>
      <Dock active="library" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />
    </Frame>
  );
}

function historySections() {
  const P = FRAMES.phone;
  return [
    { id: 'history-phone', title: '5b · History — phone (Phase 1b)',
      subtitle: 'Library → History: every track that sounded, newest first, by day. Chips filter by queue — the active queue is selected on open, the queues that live on only in the history follow, "All" last (D50). Tap a row = play now, keep my queue (inserted after the cursor, D35); ⋮ = the track menu. Clear in the header asks first.',
      boards: [
        { id: 'hs-phone', label: 'History · active queue selected', w: P.w, h: P.h, el: <PhoneHistory /> },
        { id: 'hs-all', label: 'History · "All" names the queue on every row', w: P.w, h: P.h, el: <PhoneHistory all /> },
      ] },
  ];
}

Object.assign(window, { historySections });
