import { Link } from 'react-router';
import { cn } from 'cn';
import { EmploiGoLogo } from './emploi-go-logo';

export function Brand({
  className,
  withName = true,
}: {
  className?: string;
  withName?: boolean;
}) {
  return (
    <Link
      to="/"
      aria-label="Emploi Go, accueil"
      className={cn(
        'inline-flex items-center gap-3 rounded-lg focus-visible:outline-2 focus-visible:outline-offset-4',
        className,
      )}
    >
      <EmploiGoLogo className="h-11 w-auto text-foreground" />
      {withName ? (
        <span className="flex flex-col">
          <span className="text-xl font-bold tracking-tight text-primary">
            Emploi Go
          </span>
          <span className="text-xs text-muted-foreground">
            Mission Locale de Dijon
          </span>
        </span>
      ) : null}
    </Link>
  );
}
