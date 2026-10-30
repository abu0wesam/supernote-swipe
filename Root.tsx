/**
 * Chooses between the page tool (opened from the toolbar button) and the
 * original screens of the plugin.
 *
 * @format
 */

import React, {useEffect, useReducer} from 'react';
import App from './App';
import ConvertScreen from './ConvertScreen';
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
  return getViewMode() === 'convert' ? <ConvertScreen key={convertVersion()} /> : <App />;
}
