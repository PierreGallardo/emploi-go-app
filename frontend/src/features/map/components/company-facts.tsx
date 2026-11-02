import type { EstablishmentDetail } from '../schemas';

const categoryLabels = {
  PME: 'Petite ou moyenne entreprise (PME)',
  ETI: 'Entreprise de taille intermédiaire (ETI)',
  GE: 'Grande entreprise (GE)',
} as const;

// The ranges of salaried staff of the INSEE, by their code.
const headcountLabels: Record<string, string> = {
  '00': 'Aucun salarié',
  '01': '1 ou 2 salariés',
  '02': '3 à 5 salariés',
  '03': '6 à 9 salariés',
  '11': '10 à 19 salariés',
  '12': '20 à 49 salariés',
  '21': '50 à 99 salariés',
  '22': '100 à 199 salariés',
  '31': '200 à 249 salariés',
  '32': '250 à 499 salariés',
  '41': '500 à 999 salariés',
  '42': '1 000 à 1 999 salariés',
  '51': '2 000 à 4 999 salariés',
  '52': '5 000 à 9 999 salariés',
  '53': '10 000 salariés et plus',
};

/** What the registers say of the company behind an establishment; a missing fact leaves no empty row. */
export function CompanyFacts({ detail }: { detail: EstablishmentDetail }) {
  const facts: [string, React.ReactNode][] = [['Activité', detail.activity]];
  if (detail.companyName !== detail.name)
    facts.push(['Société', detail.companyName]);
  const category =
    detail.companyCategory && categoryLabels[detail.companyCategory];
  if (category) facts.push(['Catégorie', category]);
  const headcount =
    detail.headcountRange && headcountLabels[detail.headcountRange];
  if (headcount) facts.push(['Effectif', headcount]);
  if (detail.companyCreationYear)
    facts.push(['Créée en', detail.companyCreationYear]);
  if (detail.ess) facts.push(['ESS', 'Oui']);
  facts.push(['SIRET', detail.siret]);
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-sm">
      {facts.map(([term, value]) => (
        <div key={term} className="contents">
          <dt className="text-muted-foreground">{term}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  );
}
