package com.jodae.docs199.global.security

import com.jodae.docs199.domain.user.Clearance
import com.jodae.docs199.domain.user.Position
import com.jodae.docs199.domain.user.Role
import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.UUID

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentUser

@Configuration
class CurrentUserResolver :
    HandlerMethodArgumentResolver,
    WebMvcConfigurer {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentUser::class.java) && parameter.parameterType == UserPrincipal::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any {
        val jwt =
            SecurityContextHolder.getContext().authentication?.principal as? Jwt
                ?: throw ApiException(ErrorCode.UNAUTHENTICATED)
        val userId = runCatching { UUID.fromString(jwt.subject) }.getOrElse { throw ApiException(ErrorCode.UNAUTHENTICATED) }
        return runCatching {
            UserPrincipal(
                userId,
                Role.valueOf(requireNotNull(jwt.getClaimAsString("role"))),
                UUID.fromString(requireNotNull(jwt.getClaimAsString("departmentId"))),
                Position.valueOf(requireNotNull(jwt.getClaimAsString("position"))),
                Clearance.valueOf(requireNotNull(jwt.getClaimAsString("clearance"))),
            )
        }.getOrElse { throw ApiException(ErrorCode.UNAUTHENTICATED) }
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(this)
    }
}
