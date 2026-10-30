/**
 * The plugin view: settings (from the plugin manager), the recent-files list
 * or the go-to-page picker (from a gesture). Big tap targets and no animations, for e-ink.
 *
 * @format
 */

import React, {useEffect, useReducer, useState} from 'react';
import {Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import {FileUtils, PluginManager, RattaFileSelector} from 'sn-plugin-lib';
import {FlipDistance, getFlipSettings, subscribeFlip, updateFlip} from './src/flipSettings';
import {getGotoInfo, goToPage, gotoVersion} from './src/pageflip';
import {getHistory, kindOf, subscribeHistory} from './src/recent';
import {
  ACTIONS,
  GESTURES,
  GestureId,
  getOptions,
  getSettings,
  resetSettings,
  subscribe,
  updateGesture,
  updateOptions,
} from './src/settings';
import {lastGesture, openPath, orientationInfo, refreshOrientation, subscribeGesture} from './src/switcher';
import {getViewMode, subscribeView} from './src/view';

const DOC_SUFFIXES = ['note', 'pdf', 'epub', 'cbz', 'xps', 'fb2'];

const basename = (p: string) => p.slice(p.lastIndexOf('/') + 1);
const stripExt = (n: string) => (n.lastIndexOf('.') > 0 ? n.slice(0, n.lastIndexOf('.')) : n);
const folderOf = (p: string) => {
  const dir = p.slice(0, p.lastIndexOf('/'));
  return dir.replace(/^\/storage\/emulated\/0\//, '');
};

function useRefresh(...subscriptions: ((fn: () => void) => () => void)[]) {
  const [, refresh] = useReducer((n: number) => n + 1, 0);
  useEffect(() => {
    const unsubs = subscriptions.map(sub => sub(refresh));
    return () => unsubs.forEach(u => u());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return refresh;
}

function Header({title}: {title: string}) {
  return (
    <View style={styles.header}>
      <Text style={styles.title}>{title}</Text>
      <Pressable onPress={() => PluginManager.closePluginView()} style={styles.close}>
        <Text style={styles.title}>✕</Text>
      </Pressable>
    </View>
  );
}

function Toggle({label, on, onPress}: {label: string; on: boolean; onPress: () => void}) {
  return (
    <Pressable style={[styles.chip, on && styles.chipOn]} onPress={onPress}>
      <Text style={[styles.chipText, on && styles.chipOnText]}>{label}</Text>
    </Pressable>
  );
}

async function chooseFile(id: GestureId) {
  try {
    const picked = await RattaFileSelector.selectFile({
      selectType: 1,
      suffixList: DOC_SUFFIXES,
      title: 'Choose the file to open',
    });
    if (picked && picked[0]) {
      updateGesture(id, {file: picked[0]});
    }
  } catch (e) {
    console.warn('[Swipe] selectFile', e);
  }
}

function GestureCard({id, label}: {id: GestureId; label: string}) {
  const g = getSettings()[id];
  const index = Math.max(0, ACTIONS.findIndex(a => a.id === g.action));
  const cycle = (step: number) => updateGesture(id, {action: ACTIONS[(index + step + ACTIONS.length) % ACTIONS.length].id});
  return (
    <View style={styles.card}>
      <Text style={styles.cardTitle}>{label}</Text>
      <View style={styles.row}>
        <Pressable style={styles.arrow} onPress={() => cycle(-1)}>
          <Text style={styles.arrowText}>◀</Text>
        </Pressable>
        <Text style={styles.action}>{ACTIONS[index].label}</Text>
        <Pressable style={styles.arrow} onPress={() => cycle(1)}>
          <Text style={styles.arrowText}>▶</Text>
        </Pressable>
      </View>
      {g.action === 'file' ? (
        <View style={styles.row}>
          <Text style={styles.file} numberOfLines={1}>
            {g.file ? basename(g.file) : 'No file chosen'}
          </Text>
          <Toggle label="Choose…" on={false} onPress={() => chooseFile(id)} />
        </View>
      ) : null}
      {g.action !== 'none' ? (
        <View style={styles.row}>
          <Text style={styles.hint}>Active in</Text>
          <Toggle label="Portrait" on={g.portrait} onPress={() => updateGesture(id, {portrait: !g.portrait})} />
          <Toggle label="Landscape" on={g.landscape} onPress={() => updateGesture(id, {landscape: !g.landscape})} />
        </View>
      ) : null}
    </View>
  );
}

const DISTANCES: {id: FlipDistance; label: string}[] = [
  {id: 'short', label: 'Short'},
  {id: 'normal', label: 'Normal'},
  {id: 'long', label: 'Long'},
];

function FlipCard() {
  const s = getFlipSettings();
  return (
    <View style={styles.card}>
      <Text style={styles.cardTitle}>One-finger page flip</Text>
      <View style={styles.row}>
        <Text style={[styles.hint, styles.grow]}>Flip pages with one finger (documents and notes)</Text>
        <Toggle label={s.enabled ? 'On' : 'Off'} on={s.enabled} onPress={() => updateFlip({enabled: !s.enabled})} />
      </View>
      <View style={styles.row}>
        <Text style={[styles.hint, styles.grow]}>{s.reversed ? 'Right → left = next page' : 'Left → right = next page (Arabic)'}</Text>
        <Toggle label="Reverse" on={s.reversed} onPress={() => updateFlip({reversed: !s.reversed})} />
      </View>
      <View style={styles.row}>
        <Text style={styles.hint}>Swipe length</Text>
        {DISTANCES.map(d => (
          <Toggle key={d.id} label={d.label} on={s.distance === d.id} onPress={() => updateFlip({distance: d.id})} />
        ))}
      </View>
      <View style={styles.row}>
        <Text style={[styles.hint, styles.grow]}>New page when flipping past the last page of a note</Text>
        <Toggle label={s.newPage ? 'On' : 'Off'} on={s.newPage} onPress={() => updateFlip({newPage: !s.newPage})} />
      </View>
    </View>
  );
}

function SettingsScreen() {
  const refresh = useRefresh(subscribe, subscribeGesture, subscribeFlip);
  useEffect(() => {
    refreshOrientation().then(refresh);
  }, [refresh]);
  const flip = getOptions().landscapeFlip;
  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Header title="PDF Tool settings" />
      <FlipCard />
      <Text style={styles.intro}>
        Choose what each multi-finger swipe does, and in which orientation. In landscape, two-finger swipes already
        scroll the page, so three fingers are used there by default. Pick "Go to page..." to choose a page of the open
        file.
      </Text>
      {GESTURES.map(g => (
        <GestureCard key={g.id} id={g.id} label={g.label} />
      ))}
      <View style={styles.card}>
        <View style={styles.row}>
          <Text style={[styles.hint, styles.grow]}>Swap up and down in landscape (if your swipes go the wrong way)</Text>
          <Toggle label={flip ? 'On' : 'Off'} on={flip} onPress={() => updateOptions({landscapeFlip: !flip})} />
        </View>
      </View>
      <Text style={styles.diag}>Detected now: {orientationInfo()}</Text>
      <Text style={styles.diag}>Last swipe: {lastGesture || '—'}</Text>
      <Pressable style={styles.reset} onPress={resetSettings}>
        <Text style={styles.chipText}>Reset to defaults</Text>
      </Pressable>
    </ScrollView>
  );
}

function GotoScreen() {
  const info = getGotoInfo();
  const total = info?.total ?? null;
  const [page, setPage] = useState((info?.current ?? 0) + 1);
  const clamp = (n: number) => Math.max(1, total ? Math.min(total, n) : n);
  const step = (delta: number) => setPage(p => clamp(p + delta));
  const go = () => {
    PluginManager.closePluginView();
    setTimeout(() => {
      goToPage(page - 1);
    }, 300);
  };
  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Header title="Go to page" />
      {info ? (
        <>
          <Text style={styles.intro}>
            {stripExt(basename(info.path))} · now on page {info.current + 1}
            {total ? ` of ${total}` : ''}
          </Text>
          <View style={styles.card}>
            <Text style={styles.big}>{page}</Text>
            <View style={styles.row}>
              <Toggle label="−10" on={false} onPress={() => step(-10)} />
              <Toggle label="−1" on={false} onPress={() => step(-1)} />
              <Toggle label="+1" on={false} onPress={() => step(1)} />
              <Toggle label="+10" on={false} onPress={() => step(10)} />
            </View>
            <View style={styles.row}>
              <Toggle label="−100" on={false} onPress={() => step(-100)} />
              <Toggle label="+100" on={false} onPress={() => step(100)} />
              <Toggle label="First" on={false} onPress={() => setPage(1)} />
              {total ? <Toggle label="Last" on={false} onPress={() => setPage(total)} /> : null}
            </View>
          </View>
          <Pressable style={styles.reset} onPress={go}>
            <Text style={styles.chipText}>Go to page {page}</Text>
          </Pressable>
        </>
      ) : (
        <Text style={styles.intro}>No open file was detected.</Text>
      )}
    </ScrollView>
  );
}

function RecentScreen() {
  useRefresh(subscribeHistory);
  const [existing, setExisting] = useState<string[] | null>(null);
  const history = getHistory();
  useEffect(() => {
    Promise.all(history.map(async p => ((await FileUtils.exists(p)) ? p : null)))
      .then(list => setExisting(list.filter((p): p is string => !!p)))
      .catch(() => setExisting(history));
  }, [history]);
  const open = (path: string) => {
    PluginManager.closePluginView();
    openPath(path);
  };
  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Header title="Recent files" />
      {(existing ?? history).map(p => (
        <Pressable key={p} style={styles.recent} onPress={() => open(p)}>
          <Text style={styles.badge}>{kindOf(p) === 'note' ? 'NOTE' : p.slice(p.lastIndexOf('.') + 1).toUpperCase()}</Text>
          <View style={styles.grow}>
            <Text style={styles.recentName} numberOfLines={1}>
              {stripExt(basename(p))}
            </Text>
            <Text style={styles.hint} numberOfLines={1}>
              {folderOf(p)}
            </Text>
          </View>
        </Pressable>
      ))}
      {existing && !existing.length ? <Text style={styles.intro}>No recent files yet.</Text> : null}
    </ScrollView>
  );
}

function App(): React.JSX.Element {
  useRefresh(subscribeView);
  const mode = getViewMode();
  if (mode === 'recent') {
    return <RecentScreen />;
  }
  if (mode === 'goto') {
    return <GotoScreen key={gotoVersion()} />;
  }
  return <SettingsScreen />;
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: '#ffffff'},
  content: {padding: 32},
  header: {flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center'},
  title: {fontSize: 30, fontWeight: '700', color: '#000000'},
  close: {padding: 8},
  intro: {fontSize: 19, lineHeight: 28, color: '#000000', marginVertical: 16},
  card: {borderWidth: 2, borderColor: '#000000', borderRadius: 12, padding: 16, marginBottom: 16},
  cardTitle: {fontSize: 24, fontWeight: '700', color: '#000000', marginBottom: 8},
  big: {fontSize: 64, fontWeight: '700', color: '#000000', textAlign: 'center', marginVertical: 8},
  row: {flexDirection: 'row', alignItems: 'center', marginTop: 8},
  grow: {flex: 1},
  arrow: {borderWidth: 2, borderColor: '#000000', borderRadius: 8, width: 64, height: 56, alignItems: 'center', justifyContent: 'center'},
  arrowText: {fontSize: 24, color: '#000000'},
  action: {flex: 1, fontSize: 22, color: '#000000', textAlign: 'center'},
  file: {flex: 1, fontSize: 18, color: '#000000', marginRight: 12},
  hint: {fontSize: 17, color: '#444444', marginRight: 12},
  chip: {borderWidth: 2, borderColor: '#000000', borderRadius: 8, paddingVertical: 10, paddingHorizontal: 16, marginLeft: 8},
  chipOn: {backgroundColor: '#000000'},
  chipText: {fontSize: 19, color: '#000000'},
  chipOnText: {color: '#ffffff'},
  diag: {fontSize: 15, color: '#444444', marginTop: 8, fontFamily: 'monospace'},
  reset: {alignSelf: 'flex-start', marginTop: 20, borderWidth: 2, borderColor: '#000000', borderRadius: 8, padding: 12},
  recent: {flexDirection: 'row', alignItems: 'center', paddingVertical: 18, borderBottomWidth: 1, borderColor: '#c9c9c9'},
  badge: {
    fontSize: 15,
    fontWeight: '700',
    color: '#000000',
    borderWidth: 2,
    borderColor: '#000000',
    borderRadius: 6,
    paddingHorizontal: 8,
    paddingVertical: 4,
    marginRight: 16,
    minWidth: 72,
    textAlign: 'center',
  },
  recentName: {fontSize: 22, color: '#000000'},
});

export default App;
