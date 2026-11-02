package fr.emploigo.establishment.infrastructure.synchronization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(schema = "reference", name = "communes")
public class CommuneJpaEntity {
    @Id
    @Column(name = "code", nullable = false, length = 5)
    private String code;

    protected CommuneJpaEntity() {}

    String code() {
        return code;
    }
}
