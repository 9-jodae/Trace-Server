package com.jodae.docs199.domain.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

enum class Position { STAFF, SENIOR, MANAGER, DIRECTOR }

enum class Role { EMPLOYEE, DOC_ADMIN, SYSTEM_ADMIN }

enum class Clearance { PUBLIC, INTERNAL, CONFIDENTIAL }

@Entity
@Table(name = "departments")
class Department(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false, unique = true) val name: String,
)

@Entity
@Table(name = "users")
class User(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false, unique = true) val email: String,
    @Column(name = "password_hash", nullable = false) val passwordHash: String,
    @Column(nullable = false) val name: String,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "department_id", nullable = false) val department: Department,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val position: Position,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val role: Role,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val clearance: Clearance,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) val updatedAt: Instant = Instant.now(),
)

interface DepartmentRepository : JpaRepository<Department, UUID> {
    fun findByName(name: String): Department?
}

interface UserRepository : JpaRepository<User, UUID> {
    @EntityGraph(attributePaths = ["department"])
    override fun findById(id: UUID): java.util.Optional<User>

    @EntityGraph(attributePaths = ["department"])
    fun findByEmail(email: String): User?
}
