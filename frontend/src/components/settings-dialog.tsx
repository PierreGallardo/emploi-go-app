import { useId, type RefObject } from 'react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { FieldLabel } from '@/components/ui/field';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { setTheme, useTheme, type ThemeChoice } from '@/lib/theme';

const themes: { value: ThemeChoice; label: string }[] = [
  { value: 'light', label: 'Clair' },
  { value: 'dark', label: 'Sombre' },
  { value: 'system', label: 'Système' },
];

function ThemeSetting() {
  const theme = useTheme();
  const id = useId();
  return (
    <section className="flex flex-col gap-3">
      <h3 id={`${id}-title`} className="font-medium">
        Thème
      </h3>
      <RadioGroup
        aria-labelledby={`${id}-title`}
        value={theme}
        onValueChange={(value) => setTheme(value as ThemeChoice)}
      >
        {themes.map(({ value, label }) => (
          <FieldLabel
            key={value}
            htmlFor={`${id}-${value}`}
            className="min-h-11 w-full items-center rounded-md border border-input px-3 has-focus-visible:ring-2 has-focus-visible:ring-ring"
          >
            <RadioGroupItem id={`${id}-${value}`} value={value} />
            {label}
          </FieldLabel>
        ))}
      </RadioGroup>
    </section>
  );
}

export function SettingsDialog({
  open,
  onOpenChange,
  returnFocusTo,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Opened from a menu that is already gone: Radix would not find where to return the focus. */
  returnFocusTo: RefObject<HTMLElement | null>;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className="sm:max-w-sm"
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          returnFocusTo.current?.focus();
        }}
      >
        <DialogHeader>
          <DialogTitle>Paramètres</DialogTitle>
          <DialogDescription>Réglages de cet appareil.</DialogDescription>
        </DialogHeader>
        <ThemeSetting />
      </DialogContent>
    </Dialog>
  );
}
