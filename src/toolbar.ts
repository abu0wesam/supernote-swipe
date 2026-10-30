import {PluginManager} from 'sn-plugin-lib';
import {showView} from './view';

/** Toolbar button of the document reader that opens the page tool. */
const BUTTON_ID = 301;

let stamp = 0;

/** Changes each time the button is pressed, so the screen starts afresh. */
export const convertVersion = () => stamp;

export function startToolbar(icon: string) {
  Promise.resolve(
    PluginManager.registerButton(1, ['DOC'], {
      id: BUTTON_ID,
      name: JSON.stringify({en: 'PDF Tool', zh_CN: 'PDF Tool'}),
      icon,
      showType: 1,
    } as any),
  ).catch(e => console.warn('[PDF Tool] registerButton', e));
  PluginManager.registerButtonListener({
    onButtonPress(event: {id: number}) {
      if (event.id === BUTTON_ID) {
        stamp += 1;
        showView('convert');
      }
    },
  });
}
