package fr.emploigo.establishment.infrastructure.synchronization;

import fr.emploigo.establishment.domain.TransitLine;
import fr.emploigo.establishment.domain.TransitMode;
import fr.emploigo.establishment.domain.TransitStop;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.HashSet;
import java.util.Set;

/** A stop and the names of its lines; its location is computed by the database. */
@Entity
@Table(name = "transit_stops")
public class TransitStopJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 4)
    private TransitMode mode;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    // The worker may not delete these rows: the foreign key does, as the owner of the table.
    @ElementCollection
    @OnDelete(action = OnDeleteAction.CASCADE)
    @CollectionTable(name = "transit_stop_lines", joinColumns = @JoinColumn(name = "stop_id"))
    @Column(name = "line_name", nullable = false, length = 20)
    private Set<String> lineNames = new HashSet<>();

    protected TransitStopJpaEntity() {}

    static TransitStopJpaEntity from(TransitStop stop) {
        var entity = new TransitStopJpaEntity();
        entity.name = stop.name();
        entity.mode = stop.mode();
        entity.longitude = stop.position().longitude();
        entity.latitude = stop.position().latitude();
        stop.lines().stream().map(TransitLine::name).forEach(entity.lineNames::add);
        return entity;
    }
}
