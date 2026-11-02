import { googleMessage } from '../../google-errors';
import { useStartGoogle } from '../../queries';
import {
  RetryButton,
  RetryHint,
  RetryStatus,
  useRetryHold,
} from './retry-wait';
import logo from '../assets/google-logo.webp';
import './google-button.css';
import { FormError } from './form-error';

export function GoogleButton({ disabled = false }: { disabled?: boolean }) {
  const mutation = useStartGoogle();
  // The quota counts the address of the browser, nothing that is typed: the button is held until the deadline.
  const hold = useRetryHold(mutation.error);
  return (
    <div className="flex flex-col gap-3">
      <RetryButton
        type="button"
        // Unlike outline, this variant has no dark or hover text colour that would override Google's fixed colours.
        variant="secondary"
        // Google's disabled style also applies to a held button, instead of the muted style of the other buttons.
        className="google-auth-button min-h-11 w-full gap-2.5 rounded-sm border border-[#747775] bg-white px-3 text-sm leading-5 font-medium text-[#1f1f1f] hover:bg-[#f8faff] aria-disabled:border-[#1f1f1f1f] aria-disabled:bg-[#ffffff61] aria-disabled:text-[#1f1f1f61] aria-disabled:hover:bg-[#ffffff61] aria-disabled:[&>img]:opacity-40"
        hold={hold}
        // aria-disabled rather than disabled: a disabled button drops the focus.
        sending={disabled || mutation.isPending}
        onClick={async () => {
          try {
            const uri = await mutation.mutateAsync();
            window.location.assign(uri);
          } catch {
            /* The controlled error is rendered below. */
          }
        }}
      >
        <img
          src={logo}
          alt=""
          width={20}
          height={20}
          className="size-5 shrink-0"
        />
        {mutation.isPending ? 'Ouverture de Google…' : 'Continuer avec Google'}
      </RetryButton>
      <RetryStatus hold={hold} />
      {mutation.isError ? (
        <FormError>
          <p>{googleMessage(mutation.error)}</p>
          <RetryHint hold={hold} />
        </FormError>
      ) : null}
    </div>
  );
}
