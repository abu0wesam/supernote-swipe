/**
 * @format
 */

import {AppRegistry, Image} from 'react-native';
import Root from './Root';
import {name as appName} from './app.json';

import {PluginManager} from 'sn-plugin-lib';
import {loadFlipSettings} from './src/flipSettings';
import {startPageFlip} from './src/pageflip';
import {loadSettings} from './src/settings';
import {start} from './src/switcher';
import {startToolbar} from './src/toolbar';
import {showView} from './src/view';

/** Toolbar buttons registered by the first release; the host keeps them until they are unregistered. */
const LEGACY_BUTTON_IDS = [201, 202];

AppRegistry.registerComponent(appName, () => Root);

PluginManager.init();

// The settings live in the plugin manager; the page tools are opened from the toolbar buttons of the document reader.
PluginManager.registerConfigButton();
PluginManager.registerConfigButtonListener({
  onClick() {
    showView('settings');
  },
});
loadSettings();
loadFlipSettings();

for (const id of LEGACY_BUTTON_IDS) {
  Promise.resolve()
    .then(() => PluginManager.unregisterButton(id))
    .catch(() => {});
}

start();
startPageFlip();
startToolbar(
  Image.resolveAssetSource(require('./assets/icon_pdftool.png')).uri,
  Image.resolveAssetSource(require('./assets/icon_text.png')).uri,
);
