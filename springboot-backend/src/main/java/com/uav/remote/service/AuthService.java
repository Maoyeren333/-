package com.uav.remote.service;

import com.uav.remote.entity.AppUser;
import com.uav.remote.repository.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Map;

@Service
public class AuthService {
    private final AppUserRepository users;
    private final PasswordEncoder encoder;

    public AuthService(AppUserRepository users, PasswordEncoder encoder) { this.users = users; this.encoder = encoder; }

    @Transactional
    public AppUser register(String username, String email, String password, String displayName) {
        if (username == null || username.length() < 3 || username.length() > 60) throw new IllegalArgumentException("用户名长度应为 3-60 位");
        if (email == null || !email.contains("@")) throw new IllegalArgumentException("请输入有效邮箱");
        if (password == null || password.length() < 6) throw new IllegalArgumentException("密码至少需要 6 位");
        if (users.existsByUsername(username)) throw new IllegalArgumentException("用户名已存在");
        if (users.existsByEmail(email)) throw new IllegalArgumentException("邮箱已注册");
        AppUser user = new AppUser(); user.setUsername(username); user.setEmail(email); user.setPasswordHash(encoder.encode(password)); user.setDisplayName(displayName == null || displayName.isBlank() ? username : displayName);
        return users.save(user);
    }

    public AppUser login(String account, String password) {
        AppUser user = users.findByUsername(account).or(() -> users.findByEmail(account)).orElseThrow(() -> new IllegalArgumentException("账号或密码错误"));
        if (!encoder.matches(password, user.getPasswordHash())) throw new IllegalArgumentException("账号或密码错误");
        return user;
    }

    @Transactional
    public AppUser updateProfile(AppUser user, Map<String, String> body) {
        if (body.containsKey("displayName")) user.setDisplayName(body.get("displayName"));
        if (body.containsKey("phone")) user.setPhone(body.get("phone"));
        user.setUpdatedAt(LocalDateTime.now()); return users.save(user);
    }

    @Transactional
    public void changePassword(AppUser user, String oldPassword, String newPassword) {
        if (!encoder.matches(oldPassword, user.getPasswordHash())) throw new IllegalArgumentException("原密码不正确");
        if (newPassword == null || newPassword.length() < 6) throw new IllegalArgumentException("新密码至少需要 6 位");
        user.setPasswordHash(encoder.encode(newPassword)); user.setUpdatedAt(LocalDateTime.now()); users.save(user);
    }
}
