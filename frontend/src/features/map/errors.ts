export class EstablishmentError extends Error {
  readonly status: number;
  constructor(status: number) {
    super('Lecture des établissements impossible.');
    this.status = status;
  }
}
