package fr.emploigo.establishment.domain;

import java.util.Objects;

/** One address of the Base Adresse Nationale: a number of a street in a commune. */
public record Address(String communeCode, String street, HouseNumber number) {
    public Address {
        Objects.requireNonNull(communeCode);
        Objects.requireNonNull(street);
        Objects.requireNonNull(number);
    }
}
