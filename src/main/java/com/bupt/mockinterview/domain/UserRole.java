package com.bupt.mockinterview.domain;

/**
 * 用户角色（R1-3，2026-10-02）。
 * 库中<b>第一个注册</b>的账号为 {@link #ADMIN}（全新部署引导注册），其后均为 {@link #USER}。
 * R1-3 仅持久化角色（注册接口判定），管理类接口在后续迭代使用。
 */
public enum UserRole {
    ADMIN,
    USER
}
