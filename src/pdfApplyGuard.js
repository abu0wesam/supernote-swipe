/** Keep automatic sleep disabled only while one PDF conversion owns this guard. */
export function createApplyGuard(setSleepPrevention) {
  let running = false;
  return async function run(task) {
    if (running) {
      throw new Error('A PDF conversion is already running.');
    }
    running = true;
    let attempted = false;
    try {
      attempted = true;
      const protection = await setSleepPrevention(true);
      if (protection === false || protection?.success === false) {
        throw new Error('Supernote could not prevent automatic sleep. Conversion was not started.');
      }
      return await task();
    } finally {
      try {
        if (attempted) {
          const released = await setSleepPrevention(false);
          if (released === false || released?.success === false) {
            console.warn('PDF Tool: Supernote did not confirm that automatic sleep was restored.');
          }
        }
      } catch (error) {
        console.warn('PDF Tool: could not restore automatic sleep.', error);
      } finally {
        running = false;
      }
    }
  };
}
