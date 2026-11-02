import {
  applicationChannels,
  callDirections,
  contractTypes,
  followUpChannels,
  interviewFormats,
  refusalReasons,
  visitResults,
  type FollowUpAction,
} from './follow-up-schemas';

/** Today in the visitor's calendar, as the `yyyy-mm-dd` of a date field. */
export function todayIso(now = new Date()) {
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

const dateFormat = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'long' });
export const formatActionDate = (iso: string) =>
  dateFormat.format(new Date(`${iso}T12:00:00`));

/** « 14 h 30 » for `14:30`, « 9 h » for `09:00`. */
export function formatTime(time: string) {
  const [hours, minutes] = time.split(':');
  return `${Number(hours)} h${minutes === '00' ? '' : ` ${minutes}`}`;
}

/** Whether an action is still to come: a later day, or a later hour today when it has one. */
export function isUpcoming(
  { date, time }: { date: string; time?: string | null },
  now = new Date(),
) {
  return time ? new Date(`${date}T${time}:00`) > now : date > todayIso(now);
}

type Options = readonly (readonly [string, string])[];
const labelIn = (options: Options, code: string | null) =>
  options.find(([value]) => value === code)?.[1] ?? null;

/** What an action says beyond its type, one short phrase per detail that was filled. */
export function actionDetails(action: FollowUpAction): string[] {
  const lines: (string | null)[] = [];
  switch (action.type) {
    case 'APPLICATION_SENT':
      lines.push(
        action.channel &&
          `Par ${labelIn(applicationChannels, action.channel)!.toLowerCase()}`,
        action.position && `Poste : ${action.position}`,
      );
      break;
    case 'FOLLOW_UP':
      lines.push(
        action.channel &&
          `Par ${labelIn(followUpChannels, action.channel)!.toLowerCase()}`,
      );
      break;
    case 'PHONE_CALL':
      lines.push(labelIn(callDirections, action.direction));
      break;
    case 'ON_SITE_VISIT':
      lines.push(labelIn(visitResults, action.result));
      break;
    case 'INTERVIEW':
      lines.push(
        labelIn(interviewFormats, action.format),
        action.position && `Poste : ${action.position}`,
      );
      break;
    case 'POSITIVE_ANSWER':
      lines.push(
        labelIn(contractTypes, action.contractType),
        action.startDate && `Début le ${formatActionDate(action.startDate)}`,
      );
      break;
    case 'NEGATIVE_ANSWER':
      lines.push(labelIn(refusalReasons, action.reason));
      break;
    case 'OTHER':
      lines.push(action.label);
      break;
  }
  return lines.filter((line): line is string => Boolean(line));
}
