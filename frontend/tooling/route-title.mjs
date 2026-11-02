/** A `title` returned by `meta()` would stay in the head next to the one `PageTitle` sets (see docs/frontend/conventions.md). */
const title =
  ":matches(Property[key.name='title'], Property[key.value='title'])";
export const routeTitleRestriction = {
  selector: [
    `ExportNamedDeclaration > FunctionDeclaration[id.name='meta'] ${title}`,
    `ExportNamedDeclaration > VariableDeclaration > VariableDeclarator[id.name='meta'] ${title}`,
  ].join(', '),
  message:
    'Le titre du document vient de <PageTitle> (composants), pas de meta().',
};
