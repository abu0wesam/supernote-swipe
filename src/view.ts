import {PluginManager} from 'sn-plugin-lib';

/**
 * The plugin view has several uses: the settings screen (from the plugin manager),
 * the recent-files list and the go-to-page picker (both from a gesture), and the
 * PDF and Text tools (from the toolbar buttons of the document reader).
 * This tells the root component which one to show.
 */

export type ViewMode = 'settings' | 'recent' | 'goto' | 'convert' | 'text';

let mode: ViewMode = 'settings';
const listeners = new Set<() => void>();

export const getViewMode = () => mode;

export function subscribeView(fn: () => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function showView(next: ViewMode) {
  mode = next;
  listeners.forEach(fn => fn());
  PluginManager.showPluginView();
}
