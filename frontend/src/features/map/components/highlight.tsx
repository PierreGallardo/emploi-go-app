const unaccent = (text: string) =>
  [...text]
    .map((char) => char.normalize('NFD').replace(/[̀-ͯ]/g, ''))
    .join('')
    .toLowerCase();

/** The text with the words typed in bold, whatever the accents and the case. */
export function Highlight({ text, query }: { text: string; query: string }) {
  const folded = unaccent(text);
  const marked = Array<boolean>(text.length).fill(false);
  for (const term of unaccent(query).split(/\s+/).filter(Boolean))
    for (
      let at = folded.indexOf(term);
      at >= 0;
      at = folded.indexOf(term, at + term.length)
    )
      marked.fill(true, at, at + term.length);
  const parts: { text: string; bold: boolean }[] = [];
  [...text].forEach((char, position) => {
    const last = parts[parts.length - 1];
    if (last && last.bold === marked[position]) last.text += char;
    else parts.push({ text: char, bold: marked[position]! });
  });
  return (
    <span>
      {parts.map((part, position) =>
        part.bold ? (
          <strong key={position} className="font-semibold">
            {part.text}
          </strong>
        ) : (
          <span key={position}>{part.text}</span>
        ),
      )}
    </span>
  );
}
