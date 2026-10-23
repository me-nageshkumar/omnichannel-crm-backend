package me.nageshk.omnichannel.security.auth.login;

/** Body of {@code POST /api/auth/login} ({@code username}, {@code password}) and {@code /verify-otp} ({@code otp}). */
public record LoginRequest(String username, String password, String otp) {}
