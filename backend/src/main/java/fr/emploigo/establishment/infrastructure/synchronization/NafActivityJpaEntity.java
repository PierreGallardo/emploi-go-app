package fr.emploigo.establishment.infrastructure.synchronization;

import fr.emploigo.establishment.domain.ActivityCode;
import fr.emploigo.establishment.domain.Nomenclature;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;

@Entity
@Table(schema = "reference", name = "naf_activities")
@IdClass(NafActivityJpaEntity.Key.class)
public class NafActivityJpaEntity {
    public record Key(Nomenclature nomenclature, String code) implements Serializable {}

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "nomenclature", nullable = false, length = 8)
    private Nomenclature nomenclature;

    @Id
    @Column(name = "code", nullable = false, length = 6)
    private String code;

    @Column(name = "sector_code", length = 32)
    private String sectorCode;

    protected NafActivityJpaEntity() {}

    ActivityCode toDomain() {
        return new ActivityCode(nomenclature, code);
    }
}
