import type { SignalOptions } from './dev-process.ts';
import { compareArtifacts, fetchArtifacts } from './api.ts';
import { startIsolatedBackend } from './api-isolated.ts';

export async function checkApi({ signal }: SignalOptions = {}) {
  const backend = await startIsolatedBackend({ signal });
  try {
    await compareArtifacts(
      await fetchArtifacts({ url: `${backend.baseUrl}/v3/api-docs`, signal }),
    );
  } finally {
    await backend.stop();
  }
}
