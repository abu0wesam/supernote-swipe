/**
 * Chooses between the PDF tool, the Text tool (both opened from toolbar buttons of the document
 * reader) and the original screens of the plugin.
 *
 * @format
 */

import React, {useEffect, useReducer} from 'react';
import App from './App';
import ConvertScreen from './ConvertScreen';
import TextScreen from './TextScreen';
import {convertVersion} from './src/toolbar';
import {getViewMode, subscribeView} from './src/view';

export default function Root(): React.JSX.Element {
  const [, refresh] = useReducer((n: number) => n + 1, 0);
  useEffect(() => {
    const off = subscribeView(refresh);
    return () => {
      off();
    };
  }, []);
  const mode = getViewMode();
  if (mode === 'convert') {
    return <ConvertScreen key={convertVersion()} />;
  }
  if (mode === 'text') {
    return <TextScreen key={convertVersion()} />;
  }
  return <App />;
}
