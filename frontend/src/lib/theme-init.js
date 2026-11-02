/* global document, localStorage */
// A classic script of the <head>, not bundled: it applies the stored choice of theme.ts before the first paint.
try {
  const choice = localStorage.getItem('emploi-go-theme');
  if (choice === 'light' || choice === 'dark')
    document.documentElement.dataset.theme = choice;
} catch {
  // Blocked storage: the system preference applies.
}
