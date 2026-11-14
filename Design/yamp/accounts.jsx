// accounts.jsx — Phase 2a: accounts live at once, queues bound to an account (docs/phase-2a-accounts.md;
// D22′, D56–D64). Every board here is the multi-account variant of a board elsewhere on the canvas; with one
// account those boards stay exactly as drawn (D58: no selector, no header, no marker).
// A components-and-screens module like queues.jsx: no sections of its own — each variant is registered next to
// its one-account board (YAMP.html sections 1, 4, 5, 8; radio.jsx 5c/5d; history.jsx 5b; home.jsx 11a;
// signin.jsx 9/13).
// Every top-level name here starts with `Ac` (the canvas scripts share one global scope).

// ── Sample accounts ──────────────────────────────────────────────────────────
// Two signed-in Yandex accounts used side by side for two tastes, and one removed (signed-out) account.
function useAcAccounts() {
  const t = useT();
  return {
    anna: { id: 'anna', name: t('Anna', 'Анна'), initial: t('A', 'А'), color: '#5B86DD', sub: t('Plus active · default', 'Плюс активен · основной') },
    rock: { id: 'rock', name: 'Rock', initial: 'R', color: '#D96C8A', sub: t('Plus active', 'Плюс активен') },
    old: { id: 'old', name: t('Max', 'Макс'), initial: t('M', 'М'), color: '#8A8F9C' },
  };
}

// Queues per account, in manual order (D64): each account's My Wave first.
function useAcQueues() {
  const t = useT(); const a = useAcAccounts();
  return [
    { id: 'a-wave', acc: a.anna, kind: 'wave', name: t('My Wave', 'Моя волна'), track: t('First song', 'Первая песня'), artist: t('The Band', 'Группа'), hue: 'blue', progress: 0.3 },
    { id: 'a-family', acc: a.anna, kind: 'static', name: t('With family', 'С семьёй'), track: 'Wish You Were Here', artist: 'Pink Floyd', hue: 'amber', pos: 12, total: 48, progress: 0.42, active: true },
    { id: 'a-time', acc: a.anna, kind: 'radio', name: t('Radio: Time', 'Радио: Time'), track: 'Echoes', artist: 'Pink Floyd', hue: 'violet', progress: 0.55, keep: true },
    { id: 'r-wave', acc: a.rock, kind: 'wave', name: t('My Wave', 'Моя волна'), track: 'Кукушка', artist: 'Кино', hue: 'teal', progress: 0.1 },
    { id: 'r-drive', acc: a.rock, kind: 'static', name: t('Solo drive', 'Один за рулём'), track: 'Du hast', artist: 'Rammstein', hue: 'rose', pos: 3, total: 112, progress: 0.71 },
    { id: 'r-ram', acc: a.rock, kind: 'radio', name: t('Radio: Rammstein', 'Радио: Rammstein'), track: 'Sonne', artist: 'Rammstein', hue: 'gray', progress: 0.2 },
  ];
}
function useAcRemoved() {
  const t = useT(); const a = useAcAccounts();
  return [
    { id: 'o-wave', acc: a.old, kind: 'wave', name: t('My Wave', 'Моя волна'), track: t('Old song', 'Старая песня'), artist: t('Someone', 'Кто-то'), hue: 'gray', progress: 0.6 },
    { id: 'o-trip', acc: a.old, kind: 'static', name: t('Road trip', 'Поездка'), track: 'Выхода нет', artist: 'Сплин', hue: 'gray', pos: 20, total: 64, progress: 0.3 },
  ];
}

// ── Components ───────────────────────────────────────────────────────────────
// Account marker: a disc with the account's initial in its colour; dimmed for a removed account.
function AcMark({ acc, size = 20, dim, style }) {
  return (
    <div title={acc.name} style={{ width: size, height: size, borderRadius: size / 2, flex: '0 0 auto', background: acc.color, opacity: dim ? 0.5 : 1,
      color: '#fff', fontSize: Math.round(size * 0.55), fontWeight: 800, lineHeight: `${size}px`, textAlign: 'center',
      boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.25)', ...style }}>{acc.initial}</div>
  );
}
// Section header of a grouped list (D64): marker + account name in section caps.
function AcHeader({ acc, big, style }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: big ? 12 : 8, height: big ? 46 : 32, padding: big ? '0 20px' : '0 14px', ...style }}>
      <AcMark acc={acc} size={big ? 26 : 18} />
      <div style={{ ...HFType.sectionCaps, fontSize: big ? 16 : 13, color: HF.text2 }}>{acc.name}</div>
    </div>
  );
}
// Account chips (selector in New queue / New radio): marker + name; the chosen one filled.
function AcChip({ acc, on, big }) {
  return (
    <div style={{ height: big ? 64 : 40, borderRadius: big ? 32 : 20, padding: big ? '0 24px 0 12px' : '0 14px 0 6px', display: 'inline-flex', alignItems: 'center',
      gap: big ? 12 : 8, flex: '0 0 auto', background: on ? '#fff' : HF.glassFill, color: on ? '#0B0C10' : '#fff',
      border: `1px solid ${on ? '#fff' : HF.glassBorder}`, fontSize: big ? 22 : 15, fontWeight: 700 }}>
      <AcMark acc={acc} size={big ? 40 : 28} />{acc.name}
    </div>
  );
}
function AcChips({ value = 'anna', big, style }) {
  const a = useAcAccounts();
  return <div style={{ display: 'flex', gap: big ? 14 : 8, ...style }}>{[a.anna, a.rock].map((x) => <AcChip key={x.id} acc={x} on={x.id === value} big={big} />)}</div>;
}

// A queue row as the switcher draws it, with the marker before the name (flat list) and a dimmed variant
// (a removed account's queue). Otherwise identical to the Phase 1/2 row.
const AC_ICON = { static: 'queue_music', wave: 'waves', radio: 'sensors', mix: 'auto_awesome' };
function AcRow({ q, big, mark, dim }) {
  const h = big ? 100 : 78, cover = big ? 68 : 52;
  return (
    <div style={{ height: h, borderRadius: big ? 24 : 18, padding: big ? '0 20px 0 16px' : '0 14px 0 12px', display: 'flex', alignItems: 'center', gap: big ? 18 : 12,
      opacity: dim ? 0.45 : 1,
      background: q.active ? `linear-gradient(180deg, ${HF.anodLight} 0%, ${HF.anodDark} 100%)` : 'transparent',
      boxShadow: q.active ? 'inset 0 1px 0 rgba(255,255,255,0.20), 0 0 0 1.5px rgba(201,206,216,0.50)' : 'none' }}>
      <div style={{ position: 'relative' }}>
        <Cover size={cover} hue={q.hue} radius={big ? 14 : 10} />
        <div style={{ position: 'absolute', right: -6, bottom: -6, width: big ? 28 : 22, height: big ? 28 : 22, borderRadius: 14, background: HF.sheet,
          border: `1px solid ${HF.glassBorder}`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name={AC_ICON[q.kind]} size={big ? 17 : 14} color={HF.text2} />
        </div>
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: big ? 10 : 7, minWidth: 0 }}>
          {mark && <AcMark acc={q.acc} size={big ? 26 : 18} dim={dim} />}
          <div className="hf-ellipsis" style={{ fontSize: big ? 24 : 17, lineHeight: big ? '30px' : '22px', fontWeight: 700 }}>{q.name}</div>
        </div>
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

// The collapsed group of removed accounts' queues (D62) — phone only, at the bottom of the switcher.
function AcRemovedRow({ n, open }) {
  const t = useT();
  return (
    <div style={{ height: 52, margin: '6px 6px 0', borderRadius: 16, padding: '0 14px', display: 'flex', alignItems: 'center', gap: 12,
      background: HF.glassFill, border: `1px solid ${HF.hairline}` }}>
      <Icon name="person_off" size={22} color={HF.text2} />
      <div style={{ flex: 1, fontSize: 15, fontWeight: 700, color: HF.text2 }}>{t(`Removed accounts · ${n} queues`, `Удалённые аккаунты · ${n}`)}</div>
      <Icon name={open ? 'expand_less' : 'expand_more'} size={24} color={HF.text2} />
    </div>
  );
}

// Grouped (D64 on) or flat (off) list of the signed-in accounts' queues.
function AcList({ queues, grouped, big, picker }) {
  const a = useAcAccounts();
  const gap = big ? 6 : 4;
  if (!grouped) return <div style={{ display: 'flex', flexDirection: 'column', gap }}>{queues.map((q) => <AcRow key={q.id} q={q} big={big} mark />)}</div>;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap }}>
      {[a.anna, a.rock].map((acc) => {
        const rows = queues.filter((q) => q.acc.id === acc.id);
        if (!rows.length) return null;
        return (
          <React.Fragment key={acc.id}>
            <AcHeader acc={acc} big={big} style={{ marginTop: picker ? 0 : 2 }} />
            {rows.map((q) => <AcRow key={q.id} q={q} big={big} />)}
          </React.Fragment>
        );
      })}
    </div>
  );
}

// ── Phone ────────────────────────────────────────────────────────────────────
function AcHomeCaption() {
  const t = useT(); const a = useAcAccounts();
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 4 }}>
      <AcMark acc={a.anna} size={18} /><Txt t="caption" color={HF.text2}>{t('as Anna', 'аккаунт Анна')}</Txt>
    </div>
  );
}

function AcPhoneSwitcher({ grouped, removed }) {
  const t = useT(); const queues = useAcQueues(); const gone = useAcRemoved();
  return (
    <Frame kind="phone">
      <PhoneHome dim caption={<AcHomeCaption />} />
      <Sheet height={860}>
        <QueueSheetHeader title={t('Queues', 'Очереди')} subtitle={t('Tap to switch · hold for more', 'Нажмите, чтобы переключить · удерживайте для действий')} />
        <div style={{ padding: '0 8px' }}>
          <AcList queues={queues} grouped={grouped} />
          <AcRemovedRow n={gone.length} open={removed} />
          {removed && <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginTop: 4 }}>{gone.map((q) => <AcRow key={q.id} q={q} mark dim />)}</div>}
        </div>
        {!removed && <div style={{ position: 'absolute', left: 20, right: 20, bottom: 24 }}><GlassPill icon="add">{t('New queue', 'Новая очередь')}</GlassPill></div>}
      </Sheet>
    </Frame>
  );
}

// Hold a removed account's queue: Delete only (D62).
function AcPhoneRemovedMenu() {
  const t = useT(); const q = useAcRemoved()[1];
  return (
    <Frame kind="phone">
      <PhoneHome dim />
      <Sheet height={860} style={{ opacity: 0.35 }} />
      <Sheet height={250}>
        <div style={{ padding: '18px 24px 0' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}><AcMark acc={q.acc} size={22} dim /><Txt t="dialogTitle">{q.name}</Txt></div>
          <Txt t="caption" color={HF.text2} style={{ marginBottom: 10, marginTop: 4 }}>
            {t('Account Max was removed · sign in to it again and this queue comes back', 'Аккаунт Макс удалён · войдите в него снова, и очередь вернётся')}
          </Txt>
          <MenuRow icon="delete" color={HF.danger}>{t('Delete queue', 'Удалить очередь')}</MenuRow>
        </div>
      </Sheet>
    </Frame>
  );
}

// New queue: the account selector under the name (D57/D58), default = the active queue's account.
function AcPhoneNewQueue() {
  const t = useT();
  return (
    <Frame kind="phone">
      <PhoneHome dim /><Scrim opacity={0.3} />
      <Dialog title={t('New queue', 'Новая очередь')} width={360}
        actions={<React.Fragment><DialogAction>{t('Cancel', 'Отмена')}</DialogAction><DialogAction kind="primary">{t('Create', 'Создать')}</DialogAction></React.Fragment>}>
        <Field value={t('Weekend trip', 'Поездка на выходные')} style={{ marginTop: 16 }} />
        <Txt t="caption" color={HF.text2} style={{ marginTop: 14, marginBottom: 8 }}>{t('Account', 'Аккаунт')}</Txt>
        <AcChips value="anna" />
        <Txt t="caption" color={HF.text3} style={{ marginTop: 10 }}>
          {t('The queue plays, likes and gets suggestions under this account. It can’t be changed later.', 'Очередь играет, ставит лайки и получает рекомендации от этого аккаунта. Позже его не поменять.')}
        </Txt>
      </Dialog>
    </Frame>
  );
}

// Player's track list of the active queue: the marker in the header (> 1 account).
function AcPhonePlayerQueue() {
  const t = useT(); const a = useAcAccounts();
  return (
    <Frame kind="phone" tint="#8A5A22" glowY="18%">
      <Scrim opacity={0.45} />
      <Sheet height={700}>
        <QueueSheetHeader title={<span style={{ display: 'inline-flex', alignItems: 'center', gap: 10 }}><AcMark acc={a.anna} size={26} />{t('With family', 'С семьёй')}</span>}
          subtitle={t('Queue · 12 of 48 · Anna', 'Очередь · 12 из 48 · Анна')}
          trailing={<GlassPill icon="stacks" height={44}>{t('Queues', 'Очереди')}</GlassPill>} />
        <div style={{ padding: '4px 20px' }}>
          <SectionCaps style={{ margin: '6px 0 8px' }}>{t('Now playing', 'Сейчас играет')}</SectionCaps>
          <div style={{ borderRadius: 18, padding: '0 12px', margin: '0 -8px', background: `linear-gradient(180deg, ${HF.anodLight}, ${HF.anodDark})`,
            boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.20), 0 0 0 1.5px rgba(201,206,216,0.50)' }}>
            <TrackRow title="Wish You Were Here" artist="Pink Floyd" hue="amber" trailing={<Icon name="graphic_eq" size={24} />} />
          </div>
          <SectionCaps style={{ margin: '18px 0 4px' }}>{t('Next', 'Далее')}</SectionCaps>
          <TrackRow title="Shine On You Crazy Diamond" artist="Pink Floyd" hue="amber" trailing={<GlassCircle icon="close" size={36} />} />
          <TrackRow title="Дороги" artist="Мельница" hue="green" trailing={<GlassCircle icon="close" size={36} />} />
          <TrackRow title="Comfortably Numb" artist="Pink Floyd" hue="violet" trailing={<GlassCircle icon="close" size={36} />} />
        </div>
      </Sheet>
    </Frame>
  );
}

// "Add to queue…": the same grouping as the switcher; the track joins the queue and plays under its account.
function AcPhoneAddToQueue({ grouped }) {
  const t = useT(); const queues = useAcQueues().filter((q) => q.kind === 'static').map((q) => ({ ...q, active: false }));
  return (
    <Frame kind="phone">
      <Scrim opacity={0.2} />
      <Sheet height={560}>
        <QueueSheetHeader title={t('Add to queue', 'Добавить в очередь')} subtitle="Comfortably Numb · Pink Floyd" />
        <div style={{ padding: '0 8px' }}><AcList queues={queues} grouped={grouped} /></div>
        <div style={{ position: 'absolute', left: 20, right: 20, bottom: 24 }}><GlassPill icon="add">{t('New queue', 'Новая очередь')}</GlassPill></div>
      </Sheet>
    </Frame>
  );
}

// Start radio: radios grouped as the switcher; a tap re-seeds and keeps the radio's account. "New radio"
// gets the account chips (default = the active queue's account).
function AcPhoneRadioPicker({ grouped }) {
  const t = useT(); const radios = useAcQueues().filter((q) => q.kind === 'radio').map((q) => ({ ...q, active: false }));
  return (
    <Frame kind="phone">
      <PhoneHome dim />
      <Sheet height={720}>
        <QueueSheetHeader title={t('Start radio', 'Запустить радио')} subtitle="Comfortably Numb" />
        <div style={{ padding: '0 8px' }}>
          <RdKeepRow />
          <AcList queues={radios} grouped={grouped} picker />
        </div>
        <div style={{ position: 'absolute', left: 20, right: 20, bottom: 24 }}>
          <Txt t="caption" color={HF.text2} style={{ marginBottom: 8 }}>{t('New radio on', 'Новое радио в аккаунте')}</Txt>
          <AcChips value="anna" style={{ marginBottom: 12 }} />
          <GlassPill icon="add">{t('New radio', 'Новое радио')}</GlassPill>
        </div>
      </Sheet>
    </Frame>
  );
}

// History: the queue chips carry the marker (> 1 account).
function AcPhoneHistory() {
  const t = useT(); const queues = useAcQueues().filter((q) => q.kind === 'static');
  return (
    <Frame kind="phone">
      <div style={{ position: 'absolute', left: 20, right: 20, top: 22, display: 'flex', alignItems: 'center', gap: 14 }}>
        <GlassCircle icon="arrow_back" />
        <div style={{ flex: 1, fontSize: 28, lineHeight: '34px', fontWeight: 700, letterSpacing: '-0.02em' }}>{t('History', 'История')}</div>
        <GlassCircle icon="delete" size={40} />
      </div>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 84 }}>
        <div style={{ display: 'flex', gap: 8, overflow: 'hidden', padding: '0 20px' }}>
          {queues.map((q, i) => (
            <FilterPill key={q.id} active={i === 0}><span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}><AcMark acc={q.acc} size={18} />{q.name}</span></FilterPill>
          ))}
          <FilterPill>{t('All', 'Все')}</FilterPill>
        </div>
        <div style={{ padding: '0 20px' }}>
          <HsDay first>{t('Today', 'Сегодня')}</HsDay>
          <TrackRow title="Wish You Were Here" artist="Pink Floyd" hue="amber" playing />
          <TrackRow title="Shine On You Crazy Diamond" artist="Pink Floyd" hue="amber" />
          <HsDay>{t('Yesterday', 'Вчера')}</HsDay>
          <TrackRow title="Comfortably Numb" artist="Pink Floyd" hue="violet" />
        </div>
      </div>
      <Dock active="library" title="Wish You Were Here" artist="Pink Floyd" hue="amber" />
    </Frame>
  );
}

// Settings → Accounts: no account switcher, no startup picker (D56). Add / Remove / Default per account;
// "Group" only with more than one account (D64).
function AcSettings({ multi }) {
  const t = useT(); const a = useAcAccounts(); const w = useSiWords();
  const accounts = multi ? [a.anna, a.rock] : [a.anna];
  return (
    <SiSheetPage title={t('Accounts', 'Аккаунты')}>
      <Txt t="body" color={HF.text2} style={{ padding: '0 4px' }}>
        {multi
          ? t('All accounts work at the same time. Each queue belongs to the account it was created on.', 'Все аккаунты работают одновременно. Каждая очередь принадлежит аккаунту, в котором создана.')
          : t('Signed in to Yandex Music.', 'Вход в Яндекс Музыку выполнен.')}
      </Txt>
      <SectionCaps style={{ margin: '20px 4px 10px' }}>{w.yandex}</SectionCaps>
      <SiCard>
        {accounts.map((acc, i) => (
          <div key={acc.id} style={{ display: 'flex', alignItems: 'center', gap: 14, height: 72, borderTop: i ? `1px solid ${HF.hairline}` : 'none' }}>
            <AcMark acc={acc} size={40} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <Txt t="rowTitle" ellipsis>{acc.name}</Txt>
              <Txt t="caption" color={HF.text2} ellipsis>{multi ? acc.sub : t('Plus active', 'Плюс активен')}</Txt>
            </div>
            <Icon name="more_vert" size={22} color={HF.text2} />
          </div>
        ))}
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, height: 60, borderTop: `1px solid ${HF.hairline}` }}>
          <Icon name="person_add" size={24} color={HF.text} style={{ width: 40, textAlign: 'center' }} />
          <Txt t="rowTitle">{t('Add account', 'Добавить аккаунт')}</Txt>
        </div>
      </SiCard>
      <Txt t="caption" color={HF.text3} style={{ margin: '10px 4px 0' }}>
        {multi
          ? t('⋮ on an account: Make default (new queues on first start), Remove. Removing hides its queues; signing in to it again brings them back.',
              '⋮ у аккаунта: «Сделать основным» (для очередей при первом запуске), «Удалить». После удаления его очереди скрываются; при повторном входе — возвращаются.')
          : t('⋮: Remove. Add a second account to keep two tastes apart — each queue then belongs to one of them.',
              '⋮: «Удалить». Добавьте второй аккаунт, чтобы разделить вкусы — тогда каждая очередь принадлежит одному из них.')}
      </Txt>
      {multi && (
        <React.Fragment>
          <SectionCaps style={{ margin: '22px 4px 10px' }}>{t('Queues', 'Очереди')}</SectionCaps>
          <SiCard>
            <SiSetRow first icon="account_tree" title={t('Group', 'Группировать')}
              sub={t('Off — one list, a marker on each row', 'Выкл. — один список с метками')} trailing={<Switch on />} />
          </SiCard>
        </React.Fragment>
      )}
    </SiSheetPage>
  );
}

// A removed account's queue opened from the collapsed group: its Yandex entries unavailable, no banner (D62).
function AcRemovedQueueOpen() {
  const t = useT(); const a = useAcAccounts();
  const gone = t('unavailable', 'недоступен');
  return (
    <Frame kind="phone" tint="#3A3F4C" glowY="18%">
      <Scrim opacity={0.45} />
      <Sheet height={700}>
        <QueueSheetHeader title={<span style={{ display: 'inline-flex', alignItems: 'center', gap: 10 }}><AcMark acc={a.old} size={26} dim />{t('Road trip', 'Поездка')}</span>}
          subtitle={t('Account Max removed · 20 of 64', 'Аккаунт Макс удалён · 20 из 64')}
          trailing={<GlassPill icon="stacks" height={44}>{t('Queues', 'Очереди')}</GlassPill>} />
        <div style={{ padding: '4px 20px' }}>
          <SectionCaps style={{ margin: '6px 0 4px' }}>{t('Next', 'Далее')}</SectionCaps>
          <TrackRow title="Выхода нет" artist={`Сплин · ${gone}`} hue="gray" unavailable />
          <TrackRow title="Кукушка" artist={`Кино · ${gone}`} hue="gray" unavailable />
          <TrackRow title="Штиль" artist={`Ария · ${gone}`} hue="gray" unavailable />
          <TrackRow title="Группа крови" artist={`Кино · ${gone}`} hue="gray" unavailable />
        </div>
        <Txt t="caption" color={HF.text3} style={{ position: 'absolute', left: 20, right: 20, bottom: 24 }}>
          {t('Sign in to Max again in Settings → Accounts and the queue plays as before.', 'Войдите в аккаунт Макс снова в «Настройки → Аккаунты», и очередь заиграет как прежде.')}
        </Txt>
      </Sheet>
    </Frame>
  );
}

// ── Head unit ────────────────────────────────────────────────────────────────
function AcCarPanel({ grouped, kind = 'head', w }) {
  const t = useT(); const all = useAcQueues();
  // The panel scrolls; drawn at its top: two rows per account fit beside the headers.
  const queues = all.filter((q) => ['a-wave', 'a-family', 'r-wave', 'r-drive'].includes(q.id));
  const a = useAcAccounts();
  const body = kind === 'splitN'
    ? <NarrowCarBody dim pillLead={<AcMark acc={a.anna} size={30} />} />
    : <CarHome dim w={w} pillLead={<AcMark acc={a.anna} size={30} />} />;
  return (
    <Frame kind={kind} tint="#8A5A22" glowY="40%">
      {body}
      <SidePanel width={kind === 'splitN' ? 608 : kind === 'splitW' ? 660 : 720} side="left">
        <QueueSheetHeader big title={t('Queues', 'Очереди')} trailing={<GlassCircle icon="add" size={60} />} />
        <div style={{ padding: '0 14px' }}><AcList queues={queues} grouped={grouped} big /></div>
      </SidePanel>
    </Frame>
  );
}

// Car "Start radio": radios grouped; "+" opens the account row as big chips (no text input in the car).
function AcCarRadio() {
  const t = useT(); const a = useAcAccounts();
  const radios = useAcQueues().filter((q) => q.kind === 'radio').map((q) => ({ ...q, active: false }));
  return (
    <Frame kind="head" tint="#8A5A22" glowY="40%">
      <CarHome dim pillLead={<AcMark acc={a.anna} size={30} />} />
      <SidePanel width={720} side="left">
        <QueueSheetHeader big title={t('Start radio', 'Запустить радио')} subtitle="Comfortably Numb" trailing={<GlassCircle icon="add" size={60} />} />
        <div style={{ padding: '0 14px' }}>
          <RdKeepRow big />
          <AcList queues={radios} grouped big picker />
        </div>
        <div style={{ position: 'absolute', left: 34, right: 34, bottom: 26 }}>
          <div style={{ fontSize: 17, color: HF.text2, marginBottom: 10 }}>{t('New radio on', 'Новое радио в аккаунте')}</div>
          <AcChips value="anna" big />
        </div>
      </SidePanel>
    </Frame>
  );
}

// Components sheet: the three new pieces (they go into presentation/hifi with the UI step).
function AcComponents() {
  const t = useT(); const a = useAcAccounts();
  return (
    <div className="hf" style={{ width: 760, height: 420, background: HF.sheet, padding: 28, borderRadius: 0 }}>
      <Txt t="sectionCaps" color={HF.text2} style={{ marginBottom: 12 }}>{t('Account marker', 'Метка аккаунта')}</Txt>
      <div style={{ display: 'flex', gap: 14, alignItems: 'center' }}>
        <AcMark acc={a.anna} size={18} /><AcMark acc={a.anna} size={26} /><AcMark acc={a.rock} size={40} /><AcMark acc={a.old} size={26} dim />
        <Txt t="caption" color={HF.text3}>{t('initial on the account colour · dimmed = removed account', 'инициал на цвете аккаунта · приглушённая = удалённый аккаунт')}</Txt>
      </div>
      <Txt t="sectionCaps" color={HF.text2} style={{ margin: '24px 0 6px' }}>{t('Section header (grouped list)', 'Заголовок группы')}</Txt>
      <AcHeader acc={a.rock} style={{ padding: 0 }} /><AcHeader acc={a.rock} big style={{ padding: 0 }} />
      <Txt t="sectionCaps" color={HF.text2} style={{ margin: '24px 0 10px' }}>{t('Account chips (selector)', 'Выбор аккаунта')}</Txt>
      <AcChips value="anna" />
      <AcChips value="rock" big style={{ marginTop: 12 }} />
    </div>
  );
}

function AcAnnaMark() { const a = useAcAccounts(); return <AcMark acc={a.anna} size={30} />; }

Object.assign(window, { useAcAccounts, useAcQueues, useAcRemoved, AcMark, AcHeader, AcChip, AcChips, AcRow, AcRemovedRow, AcList, AcHomeCaption,
  AcComponents, AcPhoneSwitcher, AcPhoneRemovedMenu, AcPhoneNewQueue, AcPhonePlayerQueue, AcPhoneAddToQueue, AcPhoneRadioPicker, AcPhoneHistory,
  AcSettings, AcRemovedQueueOpen, AcCarPanel, AcCarRadio, AcAnnaMark });
