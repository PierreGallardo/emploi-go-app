import { expect } from '@playwright/test';

type Message = { to: string[]; text: string };

export type Mailbox = {
  /** The newest six-digit code sent to `email`, other than `previous` when it is given. */
  code: (email: string, previous?: string) => Promise<string>;
  /** The fragment of the newest link sent to `email` under `path`. */
  link: (email: string, path: string) => Promise<string>;
};

async function messages(): Promise<Message[]> {
  const inbox = process.env.E2E_MAILBOX_URL;
  if (!inbox) throw new Error('Lancer dans l’environnement E2E isolé.');
  const response = await fetch(`${inbox}/messages`);
  if (!response.ok) throw new Error('Boîte fictive indisponible.');
  return (await response.json()) as Message[];
}

function newestText(
  all: Message[],
  email: string,
  matches: (text: string) => boolean,
) {
  return all
    .reverse()
    .find((message) => message.to.includes(email) && matches(message.text))
    ?.text;
}

export function createMailbox(): Mailbox {
  return {
    async code(email, previous) {
      const digits = /\b\d{6}\b/;
      let code: string | undefined;
      await expect
        .poll(
          async () => {
            const text = newestText(await messages(), email, (value) =>
              digits.test(value),
            );
            code = text?.match(digits)?.[0];
            return code === previous ? undefined : code;
          },
          {
            message: previous
              ? 'Aucun nouveau code dans la boîte fictive.'
              : 'Code absent de la boîte fictive.',
          },
        )
        .toBeDefined();
      return code!;
    },
    async link(email, path) {
      let fragment: string | undefined;
      await expect
        .poll(
          async () => {
            const text = newestText(await messages(), email, (value) =>
              value.includes(`${path}#`),
            );
            fragment = text?.split(`${path}#`)[1]?.split(/\s/)[0];
            return fragment;
          },
          { message: 'Lien fictif absent de la boîte fictive.' },
        )
        .toBeTruthy();
      return fragment!;
    },
  };
}
