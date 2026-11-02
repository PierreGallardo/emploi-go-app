import { authHandlers } from './auth';
import { mapHandlers } from './map';

export const handlers = [...authHandlers, ...mapHandlers];
