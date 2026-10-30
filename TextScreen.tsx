/**
 * Text tool: formats a reflowable EPUB into a new, separately named copy (font, size, weight,
 * line spacing, margins, alignment, direction). The original book is never changed.
 * An approximate preview of the page text answers every change at once.
 *
 * @format
 */

import React, {useEffect, useState} from 'react';
import {Dimensions, Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import type {TextStyle, ViewStyle} from 'react-native';
import {NativeUIUtils, PluginDocAPI, PluginFileAPI, PluginManager} from 'sn-plugin-lib';
import {MANTA_ASPECT} from './src/convert';
import {pageInfo} from './src/pageflip';
import {buildCss, defaults} from './src/textCss';
import type {TextSettings} from './src/textCss';
import {cancelText, exportTextCopy, inspectEpub, loadTextSettings, saveTextSettings, textAvailable} from './src/textTool';

type Option = [string, string | number];

const DISPLAY_HINT =
  'If the copy looks unchanged in the reader, open its Display Settings and choose "Document default setting": with "User-defined" the reader ignores most of the book formatting.';

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
  const [sample, setSample] = useState('');

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
        setMessage('The original stays unchanged.');
        try {
          const res: any = await (PluginDocAPI as any).getCurrentDocText(info.current);
          if (alive && res?.success && typeof res.result === 'string') {
            setSample(res.result.replace(/\s+/g, ' ').trim().slice(0, 1800));
          }
        } catch {
          // the preview is optional
        }
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
        'Create a separately named EPUB copy? The original is not changed. Check the result in the device reader: the preview here is only approximate.',
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
      setMessage(`Created ${result.chapters} chapters. Original unchanged.\n${result.path}\n${DISPLAY_HINT}`);
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
  const baseSize = settings.size || 18;
  const previewWidth = Math.min(Dimensions.get('window').width - 32, 560);
  const previewFrame: ViewStyle = {
    width: previewWidth,
    height: Math.round(previewWidth / MANTA_ASPECT),
    paddingHorizontal: Math.round((previewWidth * (settings.marginH >= 0 ? settings.marginH : 5)) / 100),
    paddingVertical: Math.round(baseSize * (settings.marginV >= 0 ? settings.marginV : 1)),
    direction: settings.direction === 'original' ? 'inherit' : settings.direction,
  };
  const previewText: TextStyle = {
    fontFamily: settings.font === 'original' ? undefined : settings.font,
    fontSize: baseSize,
    fontWeight: (settings.weight ? String(settings.weight) : '400') as TextStyle['fontWeight'],
    lineHeight: Math.round(baseSize * (settings.line || 1.5)),
    textAlign: settings.align === 'original' ? 'auto' : settings.align,
    color: '#000000',
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <View style={styles.header}>
        <Text style={styles.title}>Text - EPUB</Text>
        <Pressable disabled={busy} onPress={() => PluginManager.closePluginView()}>
          <Text style={styles.title}>Close</Text>
        </Pressable>
      </View>
      <Text style={styles.note}>{ready ? `${path.split('/').pop()} - ${chapters} chapters` : 'EPUB formatting'}</Text>
      <Text style={styles.note}>Approximate preview of the text of this page. The reader lays text out in its own way, so check the created copy there.</Text>
      <View style={[styles.preview, previewFrame]}>
        <Text style={previewText}>{sample || 'No page text is available for the preview.'}</Text>
      </View>
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
  preview: {borderWidth: 2, borderColor: '#000000', overflow: 'hidden', alignSelf: 'center', marginVertical: 8},
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
