import {
  BellRingIcon,
  CircleCheckIcon,
  CircleXIcon,
  DoorOpenIcon,
  MessagesSquareIcon,
  PenLineIcon,
  PhoneIcon,
  SendIcon,
  type LucideIcon,
} from 'lucide-react';
import type { ActionType } from '../follow-up-schemas';

/** One icon per type of action: the timeline and the form show the same one. */
export const actionIcons: Record<ActionType, LucideIcon> = {
  APPLICATION_SENT: SendIcon,
  FOLLOW_UP: BellRingIcon,
  PHONE_CALL: PhoneIcon,
  ON_SITE_VISIT: DoorOpenIcon,
  INTERVIEW: MessagesSquareIcon,
  POSITIVE_ANSWER: CircleCheckIcon,
  NEGATIVE_ANSWER: CircleXIcon,
  OTHER: PenLineIcon,
};
