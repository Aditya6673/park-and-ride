-- V2__create_roles.sql
-- Creates roles table, user_roles join table, and seeds the three platform roles.

CREATE TABLE roles (
    id   UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(50) NOT NULL
);

CREATE UNIQUE INDEX uk_roles_name ON roles (name);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_roles_user_id ON user_roles (user_id);

-- Seed the three platform roles — never inserted at runtime, only assigned.
INSERT INTO roles (id, name) VALUES
    (uuid_generate_v4(), 'ROLE_USER'),
    (uuid_generate_v4(), 'ROLE_OPERATOR'),
    (uuid_generate_v4(), 'ROLE_ADMIN');
