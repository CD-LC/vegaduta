package com.agenticai.core.tables;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DataTableRepository extends JpaRepository<DataTable, UUID> {

    /** Names are unique per tenant case-insensitively (index on lower(name)); the tenant schema scopes the lookup. */
    Optional<DataTable> findByNameIgnoreCase(String name);

    /** Row-locks the table for the length of the transaction, serialising concurrent inserts so the row cap holds. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from DataTable t where t.id = :id")
    Optional<DataTable> findForUpdate(@Param("id") UUID id);
}
