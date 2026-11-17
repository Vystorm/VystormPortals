package at.esoren.vystormportals;

import java.util.*;

record PortalTrip(String id, UUID owner, List<String> stops) {
    PortalTrip {
        if (id == null || !id.matches("[a-z0-9_-]{1,24}") || owner == null || stops == null || stops.size() < 2 || stops.size() > 8)
            throw new IllegalArgumentException("trip");
        stops = List.copyOf(stops);
        if (stops.stream().anyMatch(stop -> stop == null || !stop.matches("[a-z0-9_-]{1,40}")) || new HashSet<>(stops).size() != stops.size())
            throw new IllegalArgumentException("stops");
    }
}
