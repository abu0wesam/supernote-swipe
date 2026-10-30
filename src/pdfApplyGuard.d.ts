export function createApplyGuard(
  setSleepPrevention: (enabled: boolean) => unknown,
): <T>(task: () => Promise<T>) => Promise<T>;
