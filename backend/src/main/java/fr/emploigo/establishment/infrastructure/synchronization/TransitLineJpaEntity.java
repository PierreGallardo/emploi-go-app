package fr.emploigo.establishment.infrastructure.synchronization;

import fr.emploigo.establishment.domain.TransitLine;
import fr.emploigo.establishment.domain.TransitMode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "transit_lines")
public class TransitLineJpaEntity {
    @Id
    @Column(name = "name", nullable = false, length = 20)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 4)
    private TransitMode mode;

    protected TransitLineJpaEntity() {}

    static TransitLineJpaEntity from(TransitLine line) {
        var entity = new TransitLineJpaEntity();
        entity.name = line.name();
        entity.mode = line.mode();
        return entity;
    }
}
