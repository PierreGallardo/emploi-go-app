package fr.emploigo.establishment.infrastructure.synchronization;

import fr.emploigo.establishment.domain.HouseNumber;
import fr.emploigo.establishment.domain.Street;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.ArrayList;
import java.util.List;

/** A street and its numbers. */
@Entity
@Table(name = "address_streets")
public class AddressStreetJpaEntity {
    @Embeddable
    public static class HouseNumberColumns {
        @Column(name = "label", nullable = false, length = 16)
        private String label;

        @Column(name = "number", nullable = false)
        private int number;

        @Column(name = "longitude", nullable = false)
        private double longitude;

        @Column(name = "latitude", nullable = false)
        private double latitude;

        protected HouseNumberColumns() {}

        static HouseNumberColumns from(HouseNumber houseNumber) {
            var number = new HouseNumberColumns();
            number.label = houseNumber.label();
            number.number = houseNumber.number();
            number.longitude = houseNumber.position().longitude();
            number.latitude = houseNumber.position().latitude();
            return number;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "commune_code", nullable = false, length = 5)
    private String communeCode;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    // The worker may not delete these rows: the foreign key does, as the owner of the table.
    @ElementCollection
    @OnDelete(action = OnDeleteAction.CASCADE)
    @CollectionTable(name = "address_numbers", joinColumns = @JoinColumn(name = "street_id"))
    private List<HouseNumberColumns> numbers = new ArrayList<>();

    protected AddressStreetJpaEntity() {}

    static AddressStreetJpaEntity from(Street street) {
        var entity = new AddressStreetJpaEntity();
        entity.communeCode = street.communeCode();
        entity.name = street.name();
        entity.longitude = street.position().longitude();
        entity.latitude = street.position().latitude();
        street.numbers().stream().map(HouseNumberColumns::from).forEach(entity.numbers::add);
        return entity;
    }
}
