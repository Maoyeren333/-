package com.uav.remote.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 业务接口的会话校验。前端路由守卫只能拦住界面，接口本身也必须要求登录，
 * 否则未登录状态可以直接调用 /api/tasks 等业务接口。
 */
public class AuthInterceptor implements HandlerInterceptor {
    public static final String SESSION_USER_ID = "UAV_USER_ID";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        Object userId = request.getSession(false) == null ? null : request.getSession(false).getAttribute(SESSION_USER_ID);
        if (userId != null) return true;

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"登录状态已失效，请重新登录\"}");
        return false;
    }
}
