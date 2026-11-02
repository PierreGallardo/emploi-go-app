import { useRef, useState } from 'react';
import { ChevronDown, LogOut, Settings, UserRound } from 'lucide-react';
import { Brand } from '@/components/brand';
import { SettingsDialog } from '@/components/settings-dialog';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

function AccountButtonContent() {
  return (
    <>
      <UserRound data-icon="inline-start" />
      <span className="hidden sm:inline">Mon compte</span>
      <ChevronDown data-icon="inline-end" />
    </>
  );
}

/** Mounted under the account's key: another account starts with a closed menu. */
function AccountMenu({
  username,
  loggingOut,
  onLogout,
  container,
}: {
  username: string;
  loggingOut: boolean;
  onLogout: () => void;
  container: HTMLElement | null;
}) {
  const [open, setOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  return (
    <>
      <DropdownMenu modal={false} open={open} onOpenChange={setOpen}>
        <DropdownMenuTrigger asChild>
          <Button ref={trigger} variant="outline" aria-label="Mon compte">
            <AccountButtonContent />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent
          container={container}
          align="end"
          className="max-w-[calc(100vw-2rem)] min-w-52"
        >
          <DropdownMenuLabel className="break-all">
            {username}
          </DropdownMenuLabel>
          <DropdownMenuSeparator />
          <DropdownMenuGroup>
            <DropdownMenuItem onSelect={() => setSettingsOpen(true)}>
              <Settings />
              Paramètres
            </DropdownMenuItem>
            <DropdownMenuItem
              disabled={loggingOut}
              onSelect={(event) => {
                event.preventDefault();
                onLogout();
              }}
            >
              <LogOut />
              {loggingOut ? 'Déconnexion…' : 'Se déconnecter'}
            </DropdownMenuItem>
          </DropdownMenuGroup>
        </DropdownMenuContent>
      </DropdownMenu>
      <SettingsDialog
        open={settingsOpen}
        onOpenChange={setSettingsOpen}
        returnFocusTo={trigger}
      />
    </>
  );
}

/** The same element before and after the session is known, so a keyboard user keeps the focus on it. */
export function AccountHeader({
  account,
  fullBleed = false,
}: {
  account?: { username: string; loggingOut: boolean; onLogout: () => void };
  /** On a page that fills the window: the content stands at the shared map margin, not in a centred column. */
  fullBleed?: boolean;
}) {
  const [container, setContainer] = useState<HTMLElement | null>(null);
  return (
    <header ref={setContainer} className="border-b bg-card">
      <div
        className={
          fullBleed
            ? 'flex items-center justify-between gap-4 px-(--map-inset) py-3'
            : 'mx-auto flex max-w-7xl items-center justify-between gap-4 px-4 py-3 sm:px-6'
        }
      >
        <Brand withName={false} />
        {account ? (
          <AccountMenu
            key={account.username}
            {...account}
            container={container}
          />
        ) : (
          <Button variant="outline" aria-label="Mon compte" disabled>
            <AccountButtonContent />
          </Button>
        )}
      </div>
    </header>
  );
}
