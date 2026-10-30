# PDF Tool 1.4.6 hotfix

The device screenshot showed that PluginCommAPI.setSystemDormancyState is not
exposed in the installed SDK. Version 1.4.5 incorrectly blocked conversion
before the native job was started.

When this function is missing, the bridge now logs a warning and continues
through the existing single-conversion guard without sleep protection.
If the function exists, its previous behavior is unchanged.

This hotfix does NOT solve background execution or automatic sleep on devices
where that API is absent. It does not change image routines, rendering height,
compression, scheduling patch, icon, or the manual-only build trigger.
The existing tools/prepare_pdf_tool.py is still required before bundling.
No device test or Android build has been performed for this hotfix.
