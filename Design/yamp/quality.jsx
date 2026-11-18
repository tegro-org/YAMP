// Stream quality per account, chosen separately for unmetered and metered networks (tasks/todo.md, 2026-11-17).
// A components-and-screens module like jellyfin.jsx: no sections of its own. qualityBoards() returns the variants,
// placed in section 9 next to Settings → Accounts (after jf-accounts). Android's metered flag decides which choice
// applies: mobile data is metered; a Wi-Fi network can be marked metered by the user (e.g. the car's 4G travel
// router on the head unit), so the head unit picks the "metered" quality in the car and the other one at home.
// Every top-level name here starts with `Qa` (the canvas scripts share one global scope).

function QaUseWords() {
  const t = useT();
  return {
    quality: t('Quality', 'Качество'),
    unmetered: t('Unmetered (Wi-Fi)', 'Безлимитная сеть (Wi-Fi)'),
    metered: t('Metered (mobile, limited Wi-Fi)', 'Лимитная сеть (мобильная, лимитный Wi-Fi)'),
    lossless: t('Lossless (FLAC)', 'Без потерь (FLAC)'), compatible: t('Compatible (MP3)', 'Совместимое (MP3)'),
    original: t('Original', 'Оригинал'), converted: t('Converted (MP3 320)', 'Конвертированное (MP3 320)'),
    convertedNote: t('Converted can’t seek until the track is on the device.', 'В конвертированном нельзя перематывать, пока трек не скачан на устройство.'),
    originalNote: t('The file as it is on the server: seekable, no work for the server.', 'Файл как на сервере: перемотка работает, сервер не перекодирует.'),
    nowMetered: t('Now: metered', 'Сейчас: лимитная'), nowUnmetered: t('Now: unmetered', 'Сейчас: безлимитная'),
    nextTrack: t('A network change applies from the next track.', 'Смена сети действует со следующего трека.'),
    howTitle: t('Car Wi-Fi as metered', 'Wi-Fi в машине как лимитная сеть'),
    howText: t('The head unit sees the car’s 4G router as ordinary Wi-Fi. Mark that network as metered once, and YAMP uses the “Metered” quality in the car and the other one at home.',
      'Головное устройство видит 4G-роутер в машине как обычный Wi-Fi. Отметьте эту сеть как лимитную один раз — и YAMP будет брать «лимитное» качество в машине, а другое дома.'),
    howSteps: t('Android Settings → Wi-Fi → the car network → Advanced → Metered', 'Настройки Android → Wi-Fi → сеть машины → Дополнительно → Лимитная сеть'),
    openWifi: t('Open Wi-Fi settings', 'Открыть настройки Wi-Fi'),
    makeDefault: t('Make default', 'Сделать основным'), remove: t('Remove', 'Удалить'),
  };
}

// The account's quality summary for a list row: "Wi-Fi · Lossless, metered · Compatible".
function qaSummary(t, w, jf, wifi, metered) {
  return t(`Wi-Fi: ${wifi} · metered: ${metered}`, `Wi-Fi: ${wifi} · лимитная: ${metered}`);
}

// "Now: metered" chip — which of the two choices applies right now.
function QaNow({ metered = true, big }) {
  const w = QaUseWords();
  return (
    <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8, height: big ? 44 : 32, padding: big ? '0 18px' : '0 12px', borderRadius: 22,
      background: HF.glassFill, border: `1px solid ${HF.glassBorder}`, fontSize: big ? 18 : 13, fontWeight: 700 }}>
      <Icon name={metered ? 'signal_cellular_alt' : 'wifi'} size={big ? 22 : 16} color={metered ? HF.warning || '#F5B94A' : HF.success} />
      {metered ? w.nowMetered : w.nowUnmetered}
    </div>
  );
}

// One quality row: the network kind, then the choice as a segmented control.
function QaChoice({ title, icon, options, value, note, active, big }) {
  return (
    <div style={{ padding: big ? '16px 0' : '12px 0', borderTop: `1px solid ${HF.hairline}` }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: big ? 12 : 8 }}>
        <Icon name={icon} size={big ? 26 : 20} color={HF.text2} />
        <Txt t="rowTitle" style={big ? { fontSize: 21, lineHeight: '28px' } : undefined}>{title}</Txt>
        {active && <Icon name="check_circle" size={big ? 22 : 16} color={HF.success} style={{ marginLeft: 'auto' }} />}
      </div>
      <Segmented options={options} value={value} style={big ? { height: 56, borderRadius: 28 } : undefined} />
      {note && <Txt t="caption" color={HF.text3} style={{ marginTop: 6, ...(big ? { fontSize: 16, lineHeight: '22px' } : {}) }}>{note}</Txt>}
    </div>
  );
}

// The quality block of one account. `jf` switches the options to Original / Converted.
function QaQualityBlock({ jf, wifi, metered, nowMetered = true, big }) {
  const w = QaUseWords();
  const opts = jf ? [w.original, w.converted] : [w.lossless, w.compatible];
  const note = (v) => (jf ? (v === w.converted ? w.convertedNote : w.originalNote) : null);
  return (
    <SiCard style={{ padding: big ? '6px 24px 10px' : '4px 16px 8px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: big ? '12px 0' : '10px 0' }}>
        <Txt t="sectionCaps" color={HF.text2} style={big ? { fontSize: 15 } : undefined}>{w.quality}</Txt>
        <QaNow metered={nowMetered} big={big} />
      </div>
      <QaChoice big={big} icon="wifi" title={w.unmetered} options={opts} value={wifi} note={note(wifi)} active={!nowMetered} />
      <QaChoice big={big} icon="signal_cellular_alt" title={w.metered} options={opts} value={metered} note={note(metered)} active={nowMetered} />
      <Txt t="caption" color={HF.text3} style={{ padding: '8px 0 4px', ...(big ? { fontSize: 16, lineHeight: '22px' } : {}) }}>{w.nextTrack}</Txt>
    </SiCard>
  );
}

// How to mark the car's Wi-Fi as metered — a hint card with a button to the Android Wi-Fi settings.
function QaHowCard({ big }) {
  const w = QaUseWords();
  return (
    <SiCard style={{ padding: big ? '18px 24px' : '14px 16px', marginTop: big ? 14 : 10 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <Icon name="directions_car" size={big ? 26 : 20} color={HF.text2} />
        <Txt t="rowTitle" style={big ? { fontSize: 21, lineHeight: '28px' } : undefined}>{w.howTitle}</Txt>
      </div>
      <Txt t="caption" color={HF.text2} style={{ marginTop: 6, ...(big ? { fontSize: 16, lineHeight: '22px' } : {}) }}>{w.howText}</Txt>
      <Txt t="caption" color={HF.text} style={{ marginTop: 8, fontWeight: 700, ...(big ? { fontSize: 16, lineHeight: '22px' } : {}) }}>{w.howSteps}</Txt>
      <GlassPill icon="settings" height={big ? 56 : 40} style={{ marginTop: 12, ...(big ? { fontSize: 19 } : {}) }}>{w.openWifi}</GlassPill>
    </SiCard>
  );
}

// Settings → Accounts with the quality summary on each account row. Tap a row → its sheet.
function QaAccounts({ multi }) {
  const t = useT(); const w = QaUseWords(); const sw = useSiWords(); const a = useAcAccounts(); const jf = useJfAccount();
  const row = (acc, sub, i, badge) => (
    <div key={acc.id} style={{ display: 'flex', alignItems: 'center', gap: 14, minHeight: 72, padding: '8px 0', borderTop: i ? `1px solid ${HF.hairline}` : 'none' }}>
      <div style={{ position: 'relative' }}>
        <AcMark acc={acc} size={40} />
        {badge && <ProviderBadge provider="jellyfin" size={16} style={{ position: 'absolute', right: -4, bottom: -4, boxShadow: `0 0 0 2px ${HF.sheet}` }} />}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <Txt t="rowTitle" ellipsis>{acc.name}</Txt>
        <Txt t="caption" color={HF.text2} ellipsis>{sub}</Txt>
      </div>
      <Icon name="chevron_right" size={22} color={HF.text3} />
    </div>
  );
  const ya = (v1, v2) => qaSummary(t, w, false, v1, v2);
  return (
    <SiSheetPage title={sw.accounts}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 4px' }}>
        <Txt t="body" color={HF.text2}>{t('Tap an account for its quality.', 'Нажмите на аккаунт, чтобы выбрать его качество.')}</Txt>
        <QaNow metered />
      </div>
      <SectionCaps style={{ margin: '18px 4px 10px' }}>{sw.yandex}</SectionCaps>
      <SiCard>
        {row(a.anna, ya(t('FLAC', 'FLAC'), t('MP3', 'MP3')), 0)}
        {multi && row(a.rock, ya(t('FLAC', 'FLAC'), t('FLAC', 'FLAC')), 1)}
      </SiCard>
      {multi && (
        <React.Fragment>
          <SectionCaps style={{ margin: '18px 4px 10px' }}>Jellyfin</SectionCaps>
          <SiCard>{row(jf, qaSummary(t, w, true, t('original', 'оригинал'), t('original', 'оригинал')), 0, true)}</SiCard>
        </React.Fragment>
      )}
      <SiCard style={{ marginTop: 10 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, height: 60 }}>
          <Icon name="person_add" size={24} color={HF.text} style={{ width: 40, textAlign: 'center' }} />
          <Txt t="rowTitle">{t('Add account', 'Добавить аккаунт')}</Txt>
        </div>
      </SiCard>
      {multi && (
        <SiCard style={{ marginTop: 10 }}>
          <SiSetRow first icon="account_tree" title={t('Group', 'Группировать')} sub={t('Off — one list, a marker on each row', 'Выкл. — один список с метками')} trailing={<Switch on />} />
        </SiCard>
      )}
      <Txt t="caption" color={HF.text3} style={{ margin: '10px 4px 0' }}>
        {t('A new account starts with the quality chosen for the account added before it.', 'Новый аккаунт получает качество, выбранное у предыдущего.')}
      </Txt>
    </SiSheetPage>
  );
}

// One account's sheet: header with its mark, the quality block, the car Wi-Fi hint, Make default / Remove.
function QaAccountSheet({ jf: isJf, multi }) {
  const t = useT(); const w = QaUseWords(); const a = useAcAccounts(); const jf = useJfAccount();
  const acc = isJf ? jf : a.anna;
  const title = <span style={{ display: 'inline-flex', alignItems: 'center', gap: 10 }}><AcMark acc={acc} size={30} />{acc.name}</span>;
  return (
    <SiSheetPage title={title}>
      <Txt t="body" color={HF.text2} style={{ padding: '0 4px 12px' }}>
        {isJf ? `Jellyfin · ${jf.sub}` : t('Yandex Music · Plus active', 'Яндекс Музыка · Плюс активен')}
      </Txt>
      {isJf
        ? <QaQualityBlock jf wifi={w.original} metered={w.converted} />
        : <QaQualityBlock wifi={w.lossless} metered={w.compatible} />}
      <QaHowCard />
      <SiCard style={{ marginTop: 10 }}>
        {multi && <SiSetRow first icon="star" title={w.makeDefault} sub={t('Its queue opens on first start', 'Его очередь открывается при первом запуске')} />}
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, height: 60, borderTop: multi ? `1px solid ${HF.hairline}` : 'none' }}>
          <Icon name="logout" size={22} color={HF.danger || '#FF6B6B'} style={{ width: 40, textAlign: 'center' }} />
          <Txt t="rowTitle" color={HF.danger || '#FF6B6B'}>{w.remove}</Txt>
        </div>
      </SiCard>
    </SiSheetPage>
  );
}

// Settings → Playback without the old global quality row (it moved to each account).
function QaPlayback() {
  const t = useT();
  return (
    <SiSheetPage title={t('Playback', 'Воспроизведение')}>
      <SiCard>
        <SiSetRow first icon="graphic_eq" title={t('Crossfade', 'Кроссфейд')} sub={t('3 s', '3 с')} trailing={<Switch on />} />
        <SiSetRow icon="swap_horiz" title={t('Transition', 'Переход')} sub={t('Crossfade', 'Кроссфейд')} trailing={<Icon name="chevron_right" size={22} color={HF.text3} />} />
        <SiSetRow icon="sd_storage" title={t('Stream cache', 'Кэш потока')} sub={t('500 MB', '500 МБ')} trailing={<Icon name="chevron_right" size={22} color={HF.text3} />} />
        <SiSetRow icon="volume_down" title={t('Keep playing under announcements', 'Не останавливать при подсказках')} trailing={<Switch on />} />
        <SiSetRow icon="play_circle" title={t('Resume when nothing else plays', 'Продолжать, если ничего не играет')} trailing={<Switch on />} />
      </SiCard>
      <SiCard style={{ marginTop: 10, padding: '12px 16px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Icon name="high_quality" size={22} color={HF.text2} />
          <div style={{ flex: 1 }}>
            <Txt t="rowTitle">{t('Quality', 'Качество')}</Txt>
            <Txt t="caption" color={HF.text2}>{t('Now chosen per account: Settings → Accounts → the account', 'Теперь выбирается для каждого аккаунта: Настройки → Аккаунты → аккаунт')}</Txt>
          </div>
          <Icon name="chevron_right" size={22} color={HF.text3} />
        </div>
      </SiCard>
    </SiSheetPage>
  );
}

// Head unit: Settings → Accounts → an account, in the landscape settings layout.
function QaHeadSheet({ jf: isJf }) {
  const t = useT(); const w = QaUseWords(); const a = useAcAccounts(); const jf = useJfAccount();
  const acc = isJf ? jf : a.anna;
  return (
    <Frame kind="head">
      <SiHeadSettings active="hub">
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, margin: '4px 4px 12px' }}>
          <AcMark acc={acc} size={44} />
          <div style={{ fontSize: 26, lineHeight: '32px', fontWeight: 800 }}>{acc.name}</div>
          <div style={{ fontSize: 17, color: HF.text2 }}>{isJf ? 'Jellyfin' : t('Yandex Music', 'Яндекс Музыка')}</div>
        </div>
        <div style={{ display: 'flex', gap: 16 }}>
          <div style={{ flex: 1.3, minWidth: 0 }}>
            {isJf
              ? <QaQualityBlock big jf wifi={w.original} metered={w.converted} />
              : <QaQualityBlock big wifi={w.lossless} metered={w.compatible} />}
          </div>
          <div style={{ flex: 1, minWidth: 0, marginTop: -14 }}><QaHowCard big /></div>
        </div>
      </SiHeadSettings>
    </Frame>
  );
}

function qualityBoards() {
  const P = FRAMES.phone, H = FRAMES.head;
  return {
    'si-signin': [
      { after: 'jf-accounts', boards: [
        { id: 'qa-accounts-one', label: 'Quality per account · Settings → Accounts, one Yandex account: its quality summary on the row, "Now: metered"', w: P.w, h: P.h, el: <QaAccounts /> },
        { id: 'qa-accounts-multi', label: 'Quality per account · two Yandex accounts + Jellyfin, each with its own summary; tap → the account', w: P.w, h: P.h, el: <QaAccounts multi /> },
        { id: 'qa-sheet-yandex', label: 'Account → Quality (Yandex) · unmetered: Lossless (FLAC), metered: Compatible (MP3); the applying row ticked; the car Wi-Fi hint', w: P.w, h: P.h, el: <QaAccountSheet /> },
        { id: 'qa-sheet-jellyfin', label: 'Account → Quality (Jellyfin, 3 accounts) · Original / Converted (MP3 320) with the seek note; Make default, Remove', w: P.w, h: P.h, el: <QaAccountSheet jf multi /> },
        { id: 'qa-playback', label: 'Settings → Playback · no global quality row any more — a link to Accounts (alternative: keep it as "Default for new accounts")', w: P.w, h: P.h, el: <QaPlayback /> },
        { id: 'qa-head-yandex', label: 'Head unit · Accounts → Yandex account → Quality, big controls; the car Wi-Fi hint beside it', w: H.w, h: H.h, el: <QaHeadSheet /> },
        { id: 'qa-head-jellyfin', label: 'Head unit · Accounts → Jellyfin account → Quality (Original / Converted)', w: H.w, h: H.h, el: <QaHeadSheet jf /> },
      ] },
    ],
  };
}

Object.assign(window, { qualityBoards });
