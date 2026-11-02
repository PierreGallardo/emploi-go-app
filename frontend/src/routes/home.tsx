import { AuthenticatedScreen } from '@/features/auth/account/components/authenticated-screen';
import { MapScreen } from '@/features/map/components/map-screen';
import { preloadMap } from '@/features/map/map-preload';

export default function Home() {
  return (
    <AuthenticatedScreen title="Carte" fullBleed prefetch={preloadMap}>
      {(scope) => (
        <>
          <h1 className="sr-only">Carte</h1>
          <MapScreen scope={scope} />
        </>
      )}
    </AuthenticatedScreen>
  );
}
