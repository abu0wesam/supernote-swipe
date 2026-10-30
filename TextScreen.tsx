/**
 * Text tool: formats a reflowable EPUB into a new, separately named copy (font, size, weight,
 * line spacing, margins, alignment, direction). The original book is never changed.
 *
 * @format
 */

import React, {useEffect, useState} from 'react';
import {Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import {NativeUIUtils, PluginFileAPI, PluginManager} from 'sn-plugin-lib';
import {pageInfo} from './src/pageflip';
import {buildCss, defaults} from './src/textCss';
import type {TextSettings} from './src/textCss';
import {cancelText, exportTextCopy, inspectEpub, loadTextSettings, saveTextSettings, textAvailable} from './src/textTool';

type Option = [string, string | number];

function Choices({label, value, values, disabled, onChange}: {label: string; value: string | number; values: Option[]; disabled: boolean; onChange: (value: string | number) => void}) {
  return (
    <View style={styles.row}>
      <Text style={styles.label}>{label}</Text>
      <View style={styles.choices}>
        {values.map(([name, v]) => (
          <Pressable key={String(v)} disabled={disabled} onPress={() => onChange(v)} style={[styles.chip, value === v && styles.on]}>
            <Text style={[styles.chipText, value === v && styles.onText]}>{name}</Text>
          </Pressable>
        ))}
      </View>
    </View>
  );
}

const numbers = (values: number[], original: number): Option[] => values.map(n => [n === original ? 'Original' : String(n), n]);

export default function TextScreen(): React.JSX.Element {
  const [path, setPath] = useState('');
  const [settings, setSettings] = useState<TextSettings>({...defaults});
  const [chapters, setChapters] = useState(0);
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('Reading the open EPUB...');
  const [output, setOutput] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        if (!textAvailable()) {
          throw new Error('This build has no native EPUB module.');
        }
        const info = await pageInfo();
        if (!info || !/\.epub$/i.test(info.path)) {
          throw new Error('Open an EPUB book first. PDF uses the other button. TXT is not supported yet.');
        }
        const result = await inspectEpub(info.path);
        const saved = await loadTextSettings(info.path);
        if (!alive) {
          return;
        }
        setPath(info.path);
        setSettings(saved);
        setChapters(result.chapters);
        setReady(true);
        setMessage('The original stays unchanged. Reader settings may override book formatting.');
      } catch (e: any) {
        if (alive) {
          setMessage(String(e?.message ?? e));
        }
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  const update = (key: keyof TextSettings, value: string | number) => {
    setSettings(previous => ({...previous, [key]: value} as TextSettings));
    setOutput(null);
  };

  const save = async () => {
    if (!ready || busy) {
      return;
    }
    try {
      await saveTextSettings(path, settings);
      setMessage('Settings saved for this EPUB.');
    } catch (e: any) {
      setMessage(`Could not save settings: ${e?.message ?? e}`);
    }
  };

  const create = async () => {
    if (!ready || busy || !buildCss(settings)) {
      return;
    }
    setBusy(true);
    setOutput(null);
    try {
      const ok = await NativeUIUtils.showRattaDialog(
        'Create a separately named EPUB copy? The original is not changed. Review the result in the device reader; this is not a matching preview.',
        'Cancel',
        'Create copy',
        false,
      );
      if (!ok) {
        return;
      }
      setMessage('Creating the EPUB copy...');
      const result = await exportTextCopy(path, settings);
      setOutput(result.path);
      setMessage(`Created ${result.chapters} chapters. Original unchanged.\n${result.path}`);
      try {
        await saveTextSettings(path, settings);
      } catch {
        setMessage(`Copy created, but settings were not saved.\n${result.path}`);
      }
    } catch (e: any) {
      setMessage(e?.code === 'E_CANCELLED' ? 'Cancelled. Original unchanged.' : `Failed: ${e?.message ?? e}`);
    } finally {
      setBusy(false);
    }
  };

  const open = async () => {
    if (!output || busy) {
      return;
    }
    try {
      const result: any = await (PluginFileAPI as any).openFile(output, 0);
      if (result?.success === false) {
        throw new Error(result?.error?.message ?? 'Reader did not open the copy.');
      }
    } catch (e: any) {
      setMessage(`Copy exists, but could not be opened: ${e?.message ?? e}`);
    }
  };

  const disabled = busy || !ready;
  const nothingToApply = !buildCss(settings);

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <View style={styles.header}>
        <Text style={styles.title}>Text - EPUB</Text>
        <Pressable disabled={busy} onPress={() => PluginManager.closePluginView()}>
          <Text style={styles.title}>Close</Text>
        </Pressable>
      </View>
      <Text style={styles.note}>{ready ? `${path.split('/').pop()} - ${chapters} chapters` : 'EPUB formatting'}</Text>
      <Text style={styles.note}>Creates a text-based EPUB copy, not a PDF. No exact reader preview is claimed.</Text>
      <Choices label="Font" value={settings.font} values={[['Original', 'original'], ['Serif', 'serif'], ['Sans', 'sans-serif']]} disabled={disabled} onChange={v => update('font', v)} />
      <Choices label="Size (px)" value={settings.size} values={numbers([0, 16, 18, 20, 22, 24, 26, 28, 32, 36, 40], 0)} disabled={disabled} onChange={v => update('size', v)} />
      <Choices label="Weight" value={settings.weight} values={[['Original', 0], ['Normal', 400], ['Medium', 500], ['Bold', 700]]} disabled={disabled} onChange={v => update('weight', v)} />
      <Choices label="Line" value={settings.line} values={numbers([0, 1.2, 1.4, 1.6, 1.8, 2, 2.2], 0)} disabled={disabled} onChange={v => update('line', v)} />
      <Choices label="Sides (%)" value={settings.marginH} values={numbers([-1, 0, 2, 4, 6, 8, 10], -1)} disabled={disabled} onChange={v => update('marginH', v)} />
      <Choices label="Top/bottom" value={settings.marginV} values={numbers([-1, 0, 0.5, 1, 1.5, 2], -1)} disabled={disabled} onChange={v => update('marginV', v)} />
      <Choices label="Align" value={settings.align} values={[['Original', 'original'], ['Right', 'right'], ['Left', 'left'], ['Justify', 'justify']]} disabled={disabled} onChange={v => update('align', v)} />
      <Choices label="Direction" value={settings.direction} values={[['Original', 'original'], ['RTL', 'rtl'], ['LTR', 'ltr']]} disabled={disabled} onChange={v => update('direction', v)} />
      <View style={styles.actions}>
        <Pressable
          style={styles.button}
          disabled={disabled}
          onPress={() => {
            setSettings({...defaults});
            setOutput(null);
          }}>
          <Text style={styles.buttonText}>Reset controls</Text>
        </Pressable>
        <Pressable style={styles.button} disabled={disabled} onPress={save}>
          <Text style={styles.buttonText}>Save for this book</Text>
        </Pressable>
      </View>
      <Pressable style={[styles.main, (disabled || nothingToApply) && styles.disabled]} disabled={disabled || nothingToApply} onPress={create}>
        <Text style={styles.mainText}>{busy ? 'Working...' : 'Create EPUB copy'}</Text>
      </Pressable>
      {busy ? (
        <Pressable style={styles.button} onPress={() => cancelText().catch(e => setMessage(String(e)))}>
          <Text style={styles.buttonText}>Cancel</Text>
        </Pressable>
      ) : null}
      {output ? (
        <Pressable style={styles.button} onPress={open}>
          <Text style={styles.buttonText}>Open new copy</Text>
        </Pressable>
      ) : null}
      <Text selectable style={styles.note}>
        {message}
      </Text>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: '#ffffff'},
  content: {padding: 16},
  header: {flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12},
  title: {fontSize: 24, fontWeight: '700', color: '#000000'},
  note: {fontSize: 16, color: '#000000', marginVertical: 8},
  row: {marginVertical: 6},
  label: {fontSize: 17, fontWeight: '700', color: '#000000', marginBottom: 4},
  choices: {flexDirection: 'row', flexWrap: 'wrap'},
  chip: {borderWidth: 2, borderColor: '#000000', paddingHorizontal: 9, paddingVertical: 9, margin: 3, borderRadius: 5},
  on: {backgroundColor: '#000000'},
  chipText: {fontSize: 16, color: '#000000'},
  onText: {color: '#ffffff'},
  actions: {flexDirection: 'row', marginTop: 12},
  button: {borderWidth: 2, borderColor: '#000000', padding: 12, margin: 4, alignItems: 'center', flexGrow: 1},
  buttonText: {fontSize: 17, color: '#000000'},
  main: {backgroundColor: '#000000', padding: 14, alignItems: 'center', marginTop: 12},
  mainText: {color: '#ffffff', fontSize: 20, fontWeight: '700'},
  disabled: {opacity: 0.4},
});
