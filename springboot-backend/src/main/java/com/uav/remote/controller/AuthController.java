package com.uav.remote.controller;

import com.uav.remote.entity.AppUser;
import com.uav.remote.repository.AppUserRepository;
import com.uav.remote.service.AuthService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String SESSION_USER_ID = "UAV_USER_ID";
    private final AuthService auth;
    private final AppUserRepository users;
    public AuthController(AuthService auth, AppUserRepository users) { this.auth = auth; this.users = users; }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body, HttpSession session) {
        AppUser user = auth.register(body.get("username"), body.get("email"), body.get("password"), body.get("displayName"));
        session.setAttribute(SESSION_USER_ID, user.getId());
        return Map.of("user", safe(user), "message", "注册成功");
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpSession session) {
        AppUser user = auth.login(body.get("account"), body.get("password"));
        session.setAttribute(SESSION_USER_ID, user.getId());
        return Map.of("user", safe(user), "message", "登录成功");
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpSession session) { return Map.of("user", safe(current(session))); }

    @PostMapping("/logout")
    public Map<String, String> logout(HttpSession session) { session.invalidate(); return Map.of("message", "已退出登录"); }

    @PutMapping("/profile")
    public Map<String, Object> profile(@RequestBody Map<String, String> body, HttpSession session) { return Map.of("user", safe(auth.updateProfile(current(session), body))); }

    @PutMapping("/password")
    public Map<String, String> password(@RequestBody Map<String, String> body, HttpSession session) { auth.changePassword(current(session), body.get("oldPassword"), body.get("newPassword")); return Map.of("message", "密码修改成功"); }

    private AppUser current(HttpSession session) { Object id = session.getAttribute(SESSION_USER_ID); if (id == null) throw new IllegalArgumentException("请先登录"); return users.findById((Long) id).orElseThrow(() -> new IllegalArgumentException("用户不存在")); }
    private Map<String, Object> safe(AppUser u) { return Map.of("id", u.getId(), "username", u.getUsername(), "email", u.getEmail(), "displayName", u.getDisplayName() == null ? "" : u.getDisplayName(), "phone", u.getPhone() == null ? "" : u.getPhone(), "role", u.getRole(), "createdAt", u.getCreatedAt()); }
}
