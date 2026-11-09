// reference.jsx — the app as built (upstream 1.12.2): the Roborazzi golden screenshots, shown as they are.
// These are the visual reference for every new screen (D23). Source: android/app/src/test/screenshots/,
// served by Design/serve.py under /shots/.

const SHOTS = 'shots/';

// [file, label, displayed width, displayed height]
const REF_PHONE = [
  ['wave_playing', 'Home — My Wave', 412, 916],
  ['library', 'Library', 412, 916],
  ['search_results', 'Search results', 412, 916],
  ['playlist', 'Playlist', 412, 916],
  ['artist', 'Artist', 412, 916],
  ['full_player', 'Player', 412, 916],
  ['player_queue', 'Player — queue (track list)', 412, 916],
  ['track_menu', 'Track menu', 412, 916],
  ['settings', 'Settings', 412, 916],
  ['radio', 'FM radio', 412, 916],
];
const REF_WIDE = [
  ['head_wave', 'Head unit (1280×720 dp) — Home', 960, 540],
  ['head_library', 'Head unit (1280×720 dp) — Library', 960, 540],
  ['head_search', 'Head unit (1280×720 dp) — Search', 960, 540],
  ['head_playlist', 'Head unit (1280×720 dp) — Playlist', 960, 540],
  ['full_player_land', 'Landscape — Player', 1199, 539],
  ['car_landscape', 'Car mode — landscape', 1199, 539],
  ['v112_car_by_track', 'Car mode — wave by track', 1199, 539],
  ['car_radio', 'Car mode — radio', 1199, 539],
];

function Shot({ file, w, h }) {
  return <img src={SHOTS + file + '.png'} alt={file} width={w} height={h} style={{ display: 'block', width: w, height: h, background: '#05060A' }} />;
}

Object.assign(window, { REF_PHONE, REF_WIDE, Shot });
