package com.jodae.docs199.domain.user

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder

@Configuration
@Profile("local | prod")
@ConditionalOnProperty(name = ["app.demo-seed"], havingValue = "true")
class DemoUserSeed(
    @Value("\${app.demo-password}") private val password: String,
) {
    @Bean
    fun seedUsers(
        departments: DepartmentRepository,
        users: UserRepository,
        encoder: PasswordEncoder,
    ) = ApplicationRunner {
        val names = listOf("경영지원팀", "인사팀", "개발팀", "영업팀")
        val byName = names.associateWith { name -> departments.findByName(name) ?: departments.save(Department(name = name)) }
        val accounts =
            listOf(
                DemoAccount("hr.staff@local.docs199", "인사팀 사원", "인사팀", Position.STAFF, Role.EMPLOYEE, Clearance.PUBLIC),
                DemoAccount("dev.manager@local.docs199", "개발팀 팀장", "개발팀", Position.MANAGER, Role.EMPLOYEE, Clearance.INTERNAL),
                DemoAccount("ops.director@local.docs199", "경영지원팀 부장", "경영지원팀", Position.DIRECTOR, Role.EMPLOYEE, Clearance.CONFIDENTIAL),
                DemoAccount("doc.admin@local.docs199", "문서 관리자", "경영지원팀", Position.MANAGER, Role.DOC_ADMIN, Clearance.CONFIDENTIAL),
                DemoAccount("system.admin@local.docs199", "시스템 관리자", "경영지원팀", Position.DIRECTOR, Role.SYSTEM_ADMIN, Clearance.CONFIDENTIAL),
                DemoAccount("sales.manager@local.docs199", "영업팀 팀장", "영업팀", Position.MANAGER, Role.EMPLOYEE, Clearance.CONFIDENTIAL),
                DemoAccount("hr.manager@local.docs199", "인사팀 팀장", "인사팀", Position.MANAGER, Role.EMPLOYEE, Clearance.CONFIDENTIAL),
            )
        accounts.forEach { account ->
            if (users.findByEmail(account.email) == null) {
                users.save(
                    User(
                        email = account.email,
                        passwordHash = requireNotNull(encoder.encode(password)),
                        name = account.name,
                        department = byName.getValue(account.department),
                        position = account.position,
                        role = account.role,
                        clearance = account.clearance,
                    ),
                )
            }
        }
    }

    private data class DemoAccount(
        val email: String,
        val name: String,
        val department: String,
        val position: Position,
        val role: Role,
        val clearance: Clearance,
    )
}
