package com.dahla.model;

import java.util.Objects;

public class Spectator {
    private final String id;
    private final String name;

    public Spectator(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() { return id; }
    public String getName() { return name; }

    // Equals and HashCode based strictly on ID for safe removal from CopyOnWriteArrayList
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Spectator spectator = (Spectator) o;
        return id.equals(spectator.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}