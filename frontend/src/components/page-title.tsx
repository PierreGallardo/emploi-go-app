/** The only source of the document title: the routes declare no `meta()`. */
export function PageTitle({ children }: { children: string }) {
  return <title>{`${children} · Emploi Go`}</title>;
}
