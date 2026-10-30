import {PluginManager} from 'sn-plugin-lib';
import {showView} from './view';

/** Toolbar buttons of the document reader: PDF tool and Text (EPUB) tool. */
const PDF_BUTTON_ID = 301;
const TEXT_BUTTON_ID = 302;

let stamp = 0;

/** Changes each time a button is pressed, so the screen starts afresh. */
export const convertVersion = () => stamp;

function register(id: number, name: string, icon: string) {
  Promise.resolve(
    PluginManager.registerButton(1, ['DOC'], {
      id,
      name: JSON.stringify({en: name, zh_CN: name}),
      icon,
      showType: 1,
    } as any),
  ).catch(e => console.warn('[PDF Tool] registerButton', e));
}

export function startToolbar(pdfIcon: string, textIcon: string) {
  register(PDF_BUTTON_ID, 'PDF', pdfIcon);
  register(TEXT_BUTTON_ID, 'Text', textIcon);
  PluginManager.registerButtonListener({
    onButtonPress(event: {id: number}) {
      if (event.id === PDF_BUTTON_ID || event.id === TEXT_BUTTON_ID) {
        stamp += 1;
        showView(event.id === PDF_BUTTON_ID ? 'convert' : 'text');
      }
    },
  });
}
