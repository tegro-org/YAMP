// welcome.jsx — First start offers a choice of source instead of forcing a Yandex sign-in (user, 2026-11-17).
// A components-and-screens module like jellyfin.jsx: no sections of its own. welcomeBoards() returns the variants,
// placed in section 9 next to the provider chooser (jf-add): the welcome chooser (phone, head unit) and what the app
// looks like with Jellyfin only — no My Wave, the Jellyfin default queue (D83) active and still empty.
// Every top-level name here starts with `Wl` (the canvas scripts share one global scope).

function WlUseWords() {
  const t = useT();
  return {
    line: t('Where is your music?', 'Где ваша музыка?'),
    yandex: t('Yandex Music', 'Яндекс Музыка'),
    yandexSub: t('Sign in with your Yandex account — My Wave, likes, playlists', 'Войти в аккаунт Яндекса — «Моя волна», лайки, плейлисты'),
    jfSub: t('Your own music server — its address, then a code or a password', 'Свой музыкальный сервер — адрес, затем код или пароль'),
    later: t('More accounts can be added later in Settings → Accounts.', 'Другие аккаунты можно добавить позже: Настройки → Аккаунты.'),
    emptyTitle: t('The queue is empty', 'Очередь пока пуста'),
    emptyHint: t('Add music from Library or Search', 'Добавьте музыку из Библиотеки или Поиска'),
    library: t('Library', 'Музыка'), search: t('Search', 'Поиск'), home: t('Home', 'Главная'),
  };
}

// App mark: the anodized knob with the wave glyph, and the name.
function WlLogo({ big }) {
  const t = useT();
  const s = big ? 150 : 96;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
      <Knob icon="waves" size={s} iconSize={Math.round(s * 0.45)} />
      <div style={{ fontSize: big ? 56 : 40, lineHeight: big ? '64px' : '46px', fontWeight: 900, letterSpacing: '0.04em', marginTop: big ? 22 : 16 }}>YAMP</div>
      <div style={{ fontSize: big ? 20 : 15, color: HF.text2, marginTop: 4 }}>{t('Yet Another Music Player', 'Yet Another Music Player')}</div>
    </div>
  );
}
// One big choice — the jf-add row look, larger.
function WlChoice({ provider, title, sub, big }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: big ? 22 : 16, minHeight: big ? 132 : 96, padding: big ? '0 28px' : '0 18px', borderRadius: big ? 30 : 24,
      background: HF.glassFill, border: `1px solid ${HF.glassBorder}`, boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.10)', flex: big ? 1 : undefined }}>
      <ProviderBadge provider={provider} size={big ? 64 : 48} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: big ? 30 : 20, lineHeight: big ? '36px' : '26px', fontWeight: 800 }}>{title}</div>
        <div style={{ fontSize: big ? 19 : 14, lineHeight: big ? '25px' : '19px', color: HF.text2, marginTop: 4 }}>{sub}</div>
      </div>
      <Icon name="chevron_right" size={big ? 36 : 26} color={HF.text3} />
    </div>
  );
}

// 1 · Phone welcome.
function WlWelcomePhone() {
  const w = WlUseWords();
  return (
    <Frame kind="phone" glowY="30%">
      <div style={{ position: 'absolute', left: 0, right: 0, top: 120 }}><WlLogo /></div>
      <div style={{ position: 'absolute', left: 20, right: 20, top: 420 }}>
        <div style={{ fontSize: 22, fontWeight: 800, textAlign: 'center', marginBottom: 18 }}>{w.line}</div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <WlChoice provider="yandex" title={w.yandex} sub={w.yandexSub} />
          <WlChoice provider="jellyfin" title="Jellyfin" sub={w.jfSub} />
        </div>
        <Txt t="caption" color={HF.text3} style={{ marginTop: 18, textAlign: 'center' }}>{w.later}</Txt>
      </div>
    </Frame>
  );
}
// 2 · Head unit welcome: logo on the left, the two choices side by side.
function WlWelcomeHead() {
  const w = WlUseWords();
  return (
    <Frame kind="head" glowY="40%">
      <div style={{ position: 'absolute', left: 110, top: 170, width: 420 }}><WlLogo big /></div>
      <div style={{ position: 'absolute', left: 640, right: 90, top: 190 }}>
        <div style={{ fontSize: 34, fontWeight: 800, marginBottom: 26 }}>{w.line}</div>
        <div style={{ display: 'flex', gap: 24 }}>
          <WlChoice big provider="yandex" title={w.yandex} sub={w.yandexSub} />
          <WlChoice big provider="jellyfin" title="Jellyfin" sub={w.jfSub} />
        </div>
        <div style={{ fontSize: 19, color: HF.text3, marginTop: 24 }}>{w.later}</div>
      </div>
    </Frame>
  );
}

// The empty-queue card: what to do next, with the two ways there.
function WlEmptyCard({ big }) {
  const w = WlUseWords();
  return (
    <div style={{ borderRadius: big ? 30 : 24, padding: big ? '30px 32px' : '20px 18px', background: HF.glassFill, border: `1px dashed ${HF.glassBorder}` }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: big ? 18 : 12 }}>
        <Icon name="queue_music" size={big ? 40 : 28} color={HF.text2} />
        <div>
          <div style={{ fontSize: big ? 28 : 19, fontWeight: 800 }}>{w.emptyTitle}</div>
          <div style={{ fontSize: big ? 20 : 14, color: HF.text2, marginTop: 2 }}>{w.emptyHint}</div>
        </div>
      </div>
      <div style={{ display: 'flex', gap: big ? 16 : 10, marginTop: big ? 24 : 16 }}>
        <GlassPill icon="library_music" height={big ? 76 : 48} style={{ flex: 1, fontSize: big ? 22 : undefined }}>{w.library}</GlassPill>
        <GlassPill icon="search" height={big ? 76 : 48} style={{ flex: 1, fontSize: big ? 22 : undefined }}>{w.search}</GlassPill>
      </div>
    </div>
  );
}

// 3a · Home with Jellyfin only: no My Wave; the default queue is the active one and still empty.
function WlJfHome() {
  const t = useT(); const w = WlUseWords(); const jf = useJfAccount();
  return (
    <Frame kind="phone" tint="#5A3E8C">
      <div style={{ position: 'absolute', left: 20, right: 20, top: 22, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <LargeTitle>{w.home}</LargeTitle>
        <GlassCircle icon="stacks" badge={1} />
      </div>
      <div style={{ position: 'absolute', left: 16, right: 16, top: 90 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
          <AcMark acc={jf} size={22} /><Txt t="rowTitle">{jf.name}</Txt>
          <Txt t="caption" color={HF.text3}>{t('· active queue', '· активная очередь')}</Txt>
        </div>
        <WlEmptyCard />
        <SectionCaps style={{ margin: '24px 4px 10px' }}>{t('In your library', 'В вашей музыке')}</SectionCaps>
        <div style={{ display: 'flex', gap: 12 }}>
          {[['Night Drive', 'Lumen Lane', 'violet'], ['Field Notes', 'Field Notes', 'blue'], ['Copper Sky', 'Hollow Pines', 'amber']].map(([a, b, hue]) => (
            <div key={a} style={{ width: 118 }}>
              <Cover size={118} hue={hue} radius={18} />
              <Txt t="cardTitle" ellipsis style={{ marginTop: 8 }}>{a}</Txt><Txt t="caption" color={HF.text2} ellipsis>{b}</Txt>
            </div>
          ))}
        </div>
      </div>
      <HmPhoneChrome sit={HM_SIT.b} active="home" />
    </Frame>
  );
}
// 3b · Library with Jellyfin only: the account's section (albums, artists, playlists, genres), no badges.
function WlJfLibrary() {
  const t = useT(); const jf = useJfAccount();
  return (
    <Frame kind="phone" tint="#5A3E8C">
      <LargeTitle style={{ position: 'absolute', left: 20, top: 22 }}>{t('Library', 'Музыка')}</LargeTitle>
      <div style={{ position: 'absolute', left: 16, right: 16, top: 96 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, margin: '0 4px 6px' }}>
          <AcMark acc={jf} size={22} /><Txt t="rowTitle" style={{ flex: 1 }}>{jf.name}</Txt>
          <Txt t="caption" color={HF.text3}>{jf.version}</Txt>
        </div>
        <NavRow icon="album" title={t('Albums', 'Альбомы')} count={2} />
        <NavRow icon="person" title={t('Artists', 'Исполнители')} count={1} />
        <NavRow icon="queue_music" title={t('Playlists', 'Плейлисты')} count={1} />
        <NavRow icon="category" title={t('Genres', 'Жанры')} count={3} last />
        <Txt t="caption" color={HF.text3} style={{ margin: '14px 4px 0' }}>
          {t('Hold an album or artist to add it to the queue.', 'Удерживайте альбом или исполнителя, чтобы добавить в очередь.')}
        </Txt>
      </div>
      <HmPhoneChrome sit={HM_SIT.b} active="library" />
    </Frame>
  );
}
// 4 · Car home with Jellyfin only: the queue pill with the Jellyfin mark, the empty-queue card in car sizes.
function WlJfCarHome() {
  const t = useT(); const jf = useJfAccount();
  return (
    <Frame kind="head" tint="#5A3E8C" glowY="40%">
      <div style={{ position: 'absolute', left: 60, top: 60, display: 'flex', alignItems: 'center', gap: 16, height: 76, padding: '0 28px 0 18px', borderRadius: 38,
        background: HF.glassFill, border: `1px solid ${HF.glassBorder}` }}>
        <AcMark acc={jf} size={34} />
        <div style={{ fontSize: 26, fontWeight: 800 }}>{jf.name}</div>
        <div style={{ fontSize: 20, color: HF.text2 }}>{t('· empty', '· пусто')}</div>
        <Icon name="expand_more" size={30} color={HF.text2} />
      </div>
      <div style={{ position: 'absolute', left: 60, top: 170, width: 860 }}><WlEmptyCard big /></div>
      <div style={{ position: 'absolute', left: 1000, top: 70, width: 860 }}>
        <div style={{ ...HFType.sectionCaps, fontSize: 15, color: HF.text2, marginBottom: 14 }}>{t('In your library', 'В вашей музыке')}</div>
        <div style={{ display: 'flex', gap: 24 }}>
          {[['Night Drive', 'Lumen Lane', 'violet'], ['Field Notes', 'Field Notes', 'blue'], ['Copper Sky', 'Hollow Pines', 'amber']].map(([a, b, hue]) => (
            <div key={a} style={{ width: 240 }}>
              <Cover size={240} hue={hue} radius={26} />
              <div className="hf-ellipsis" style={{ fontSize: 22, fontWeight: 700, marginTop: 10 }}>{a}</div>
              <div style={{ fontSize: 17, color: HF.text2 }}>{b}</div>
            </div>
          ))}
        </div>
      </div>
      <HmDock sit={HM_SIT.b} active="car" />
    </Frame>
  );
}

function welcomeBoards() {
  const P = FRAMES.phone, H = FRAMES.head;
  return {
    'si-signin': [
      { after: 'jf-add', boards: [
        { id: 'wl-welcome', label: 'First start · choose the source: Yandex Music or Jellyfin (no forced Yandex sign-in)', w: P.w, h: P.h, el: <WlWelcomePhone /> },
        { id: 'wl-welcome-head', label: 'First start · head unit 1920×720 · the two choices side by side', w: H.w, h: H.h, el: <WlWelcomeHead /> },
        { id: 'wl-jf-home', label: 'Jellyfin only · Home: no My Wave, the default queue (D83) active and empty — "Add music from Library or Search"', w: P.w, h: P.h, el: <WlJfHome /> },
        { id: 'wl-jf-library', label: 'Jellyfin only · Library: the account’s section, no badges', w: P.w, h: P.h, el: <WlJfLibrary /> },
        { id: 'wl-jf-car', label: 'Jellyfin only · car home: queue pill with the Jellyfin mark, empty-queue card in car sizes', w: H.w, h: H.h, el: <WlJfCarHome /> },
      ] },
    ],
  };
}

Object.assign(window, { WlWelcomePhone, WlWelcomeHead, WlJfHome, WlJfLibrary, WlJfCarHome, welcomeBoards });
