package at.esoren.vystormportals;

import Vystorm.vystorm_core.visual.Point3;
import java.util.*;
import java.util.function.LongSupplier;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.util.Vector;

/** Private saved itineraries. Runtime sessions never survive disconnect or restart, and never alter portal links. */
final class PortalTrips implements Listener {
    private record Preview(PortalTrip trip, List<Portal> portals, long expires) {}
    private static final class Journey {
        final Preview preview;
        final long expires;
        Location anchor;
        int next = 1;
        long due, legDeadline;
        Journey(Preview preview, Location anchor, long now) {
            this.preview = preview;
            this.anchor = anchor.clone();
            expires = now + 180000;
            waitAt(now, 0);
        }
        void waitAt(long now, int stop) {
            due = now + Math.max(1000, preview.portals().get(stop).warmupTicks() * 50L) + (stop == 0 ? 0 : 3000);
            legDeadline = due + 15000;
        }
    }
    private final PortalsPlugin plugin;
    private final DestinationChunks chunks;
    private final TripStore store;
    private final LongSupplier clock;
    private final Map<UUID, Preview> previews = new HashMap<>();
    private final Map<UUID, Journey> active = new HashMap<>();
    private final Set<UUID> moving = new HashSet<>();
    PortalTrips(PortalsPlugin plugin, DestinationChunks chunks) throws Exception {
        this(plugin, chunks, System::currentTimeMillis);
    }
    PortalTrips(PortalsPlugin plugin, DestinationChunks chunks, LongSupplier clock) throws Exception {
        this.plugin = plugin;
        this.chunks = chunks;
        this.clock = clock;
        store = new TripStore(plugin.getDataFolder().toPath().resolve("trips.yml"), plugin.core().data().files());
    }
    boolean active(Player player) {
        return active.containsKey(player.getUniqueId());
    }
    void close() {
        active.clear();
        previews.clear();
        moving.clear();
    }
    List<String> suggest(Player player, String[] args) {
        if (!player.hasPermission("vystormportals.use") || args.length == 0) return List.of();
        String action = args[0].toLowerCase(Locale.ROOT), prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> candidates;
        if (args.length == 1) candidates = List.of("save", "list", "show", "start", "confirm", "cancel", "delete");
        else if (args.length == 2 && Set.of("show", "start", "delete").contains(action))
            candidates = store.all(player.getUniqueId()).stream().map(PortalTrip::id).sorted().toList();
        else if (action.equals("save") && args.length >= 3 && args.length <= 10) {
            Set<String> used = new HashSet<>(Arrays.stream(args).skip(2).limit(args.length - 3).map(s -> s.toLowerCase(Locale.ROOT)).toList());
            candidates = plugin.portals().values().stream().filter(p -> !used.contains(p.id()) && !p.paused() && plugin.allowed(player, p)
                    && plugin.getServer().getWorld(p.frame().world()) != null).map(Portal::id).sorted().toList();
        } else return List.of();
        return candidates.stream().filter(candidate -> candidate.startsWith(prefix)).toList();
    }
    void command(Player player, String raw) {
        if (!player.hasPermission("vystormportals.use")) {
            plugin.say(player, "denied");
            return;
        }
        String[] args = raw == null || raw.isBlank() ? new String[]{"list"} : raw.strip().split("\\s+");
        String action = args[0].toLowerCase(Locale.ROOT), id = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        UUID owner = player.getUniqueId();
        boolean valid = switch (action) {
            case "cancel", "confirm", "list" -> args.length == 1;
            case "delete", "show", "start" -> args.length == 2;
            case "save" -> args.length >= 4 && args.length <= 10;
            default -> false;
        };
        if (!valid) {
            plugin.say(player, "trip-usage");
            return;
        }
        try {
            if (action.equals("cancel")) {
                cancel(player, "trip-cancelled");
                return;
            }
            if (action.equals("confirm")) {
                confirm(player);
                return;
            }
            if (action.equals("list")) {
                plugin.say(player, "trip-list", String.join(", ", store.all(owner).stream().map(PortalTrip::id).toList()));
                return;
            }
            if (action.equals("save") && args.length >= 4) {
                PortalTrip trip = new PortalTrip(id, owner, Arrays.stream(args).skip(2).map(s -> s.toLowerCase(Locale.ROOT)).toList());
                if (resolve(player, trip) == null) {
                    plugin.say(player, "trip-unavailable");
                    return;
                }
                store.put(trip);
                cancel(player, null);
                plugin.say(player, "trip-saved", id);
                return;
            }
            PortalTrip trip = store.get(owner, id);
            if (trip == null) {
                plugin.say(player, "trip-missing");
                return;
            }
            if (action.equals("delete")) {
                store.remove(trip);
                cancel(player, null);
                plugin.say(player, "trip-deleted", id);
                return;
            }
            if (action.equals("show") || action.equals("start")) {
                List<Portal> route = resolve(player, trip);
                if (route == null) {
                    plugin.say(player, "trip-unavailable");
                    return;
                }
                plugin.say(player, "trip-preview", id, route.size());
                for (int i = 0; i < route.size(); i++) {
                    Portal portal = route.get(i);
                    Point3 at = portal.frame().origin();
                    World world = plugin.getServer().getWorld(portal.frame().world());
                    plugin.say(player, "trip-stop", i + 1, portal.id(), world.getName(), (int)Math.floor(at.x()), (int)Math.floor(at.y()), (int)Math.floor(at.z()), portal.warmupTicks() / 20.0);
                }
                if (action.equals("start")) {
                    if (active(player)) {
                        plugin.say(player, "trip-busy");
                        return;
                    }
                    previews.put(owner, new Preview(trip, route, clock.getAsLong() + 30000));
                    plugin.say(player, "trip-confirm");
                }
                return;
            }
        } catch (IllegalArgumentException invalid) {
            plugin.say(player, "trip-usage");
            return;
        } catch (java.io.IOException failure) {
            plugin.getLogger().warning("Cannot save portal itinerary: " + failure);
            plugin.say(player, "failed");
            return;
        }
        plugin.say(player, "trip-usage");
    }
    private List<Portal> resolve(Player player, PortalTrip trip) {
        List<Portal> route = new ArrayList<>();
        for (String id : trip.stops()) {
            Portal portal = plugin.portals().get(id);
            if (portal == null || portal.paused() || !plugin.hoursOpen(portal) || !plugin.allowed(player, portal) || plugin.getServer().getWorld(portal.frame().world()) == null) return null;
            route.add(portal);
        }
        return List.copyOf(route);
    }
    private void confirm(Player player) {
        Preview preview = previews.remove(player.getUniqueId());
        long now = clock.getAsLong();
        if (preview == null || now >= preview.expires()) {
            plugin.say(player, "trip-expired");
            return;
        }
        if (active.size() >= 16 || active(player)) {
            plugin.say(player, "trip-busy");
            return;
        }
        if (!preview.portals().equals(resolve(player, preview.trip()))) {
            plugin.say(player, "trip-unavailable");
            return;
        }
        Portal origin = preview.portals().getFirst();
        Location at = player.getLocation();
        Point3 p = origin.frame().origin();
        if (!at.getWorld().getUID().equals(origin.frame().world()) || at.distanceSquared(new Location(at.getWorld(), p.x(), p.y(), p.z())) > 16
                || player.isInsideVehicle() || !player.getPassengers().isEmpty()) {
                    plugin.say(player, "trip-origin", origin.id());
                    return;
                }
        active.put(player.getUniqueId(), new Journey(preview, at, now));
        plugin.say(player, "trip-started", preview.trip().id());
    }
    void tick(long serverTick) {
        long now = clock.getAsLong();
        previews.entrySet().removeIf(entry -> now >= entry.getValue().expires());
        for (var entry : List.copyOf(active.entrySet())) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            Journey journey = entry.getValue();
            if (player == null || !player.isOnline()) {
                active.remove(entry.getKey());
                continue;
            }
            if (player.isDead() || player.isInsideVehicle() || !player.getPassengers().isEmpty() || !sameSpot(player.getLocation(), journey.anchor)) {
                cancel(player, "trip-cancelled");
                continue;
            }
            if (now >= journey.expires || now >= journey.legDeadline) {
                cancel(player, "trip-timeout");
                continue;
            }
            if (!journey.preview.portals().equals(resolve(player, journey.preview.trip()))) {
                cancel(player, "trip-unavailable");
                continue;
            }
            Portal destination = journey.preview.portals().get(journey.next);
            chunks.touch(destination.frame(), serverTick);
            if (now < journey.due) continue;
            World world = plugin.getServer().getWorld(destination.frame().world());
            Point3 point = destination.frame().global(new Point3(0, .05, .8));
            Location arrival = new Location(world, point.x(), point.y(), point.z(), player.getLocation().getYaw(), player.getLocation().getPitch());
            if (!world.isChunkLoaded(arrival.getBlockX()>>4, arrival.getBlockZ()>>4)) continue;
            if (!plugin.safe(arrival, player.getHeight())) {
                cancel(player, "trip-unsafe");
                continue;
            }
            boolean travelled;
            moving.add(player.getUniqueId());
            try {
                travelled = player.teleport(arrival, PlayerTeleportEvent.TeleportCause.PLUGIN);
            } catch (RuntimeException failure) {
                cancel(player, "trip-unavailable");
                plugin.getLogger().warning("Portal itinerary teleport failed: " + failure);
                continue;
            }
            finally {
                moving.remove(player.getUniqueId());
            }
            if (active.get(player.getUniqueId()) != journey) continue;
            if (!player.isOnline() || player.isDead()) {
                cancel(player, null);
                continue;
            }
            if (!travelled) {
                cancel(player, "cancelled");
                continue;
            }
            if (!sameSpot(player.getLocation(), arrival)) {
                cancel(player, "trip-unavailable");
                continue;
            }
            player.setVelocity(new Vector());
            player.setFallDistance(0);
            journey.anchor = player.getLocation().clone();
            plugin.tripCooldown(player);
            plugin.say(player, "trip-arrived", destination.id(), journey.next + 1, journey.preview.portals().size());
            if (++journey.next >= journey.preview.portals().size()) {
                active.remove(player.getUniqueId());
                plugin.say(player, "trip-complete", journey.preview.trip().id());
            } else journey.waitAt(now, journey.next - 1);
        }
    }
    static boolean sameSpot(Location at, Location anchor) {
        return Objects.equals(at.getWorld(), anchor.getWorld()) && at.distanceSquared(anchor) <= 1.25 * 1.25;
    }
    private void cancel(Player player, String message) {
        active.remove(player.getUniqueId());
        previews.remove(player.getUniqueId());
        if (message != null) plugin.say(player, message);
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        cancel(event.getPlayer(), null);
    }
    @EventHandler public void respawn(PlayerRespawnEvent event) {
        cancel(event.getPlayer(), null);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void move(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent || event.getTo() == null) return;
        Journey journey = active.get(event.getPlayer().getUniqueId());
        if (journey != null && !sameSpot(event.getTo(), journey.anchor)) cancel(event.getPlayer(), "trip-cancelled");
    }
    /** A foreign teleport ends the itinerary: read in Core's stage OBSERVE, so only for a teleport nobody cancelled. */
    void handleSharedEvents(Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(plugin, PlayerTeleportEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::teleport);
    }
    /** Stage OBSERVE, registered in {@link #handleSharedEvents}. */
    public void teleport(PlayerTeleportEvent event) {
        if (active(event.getPlayer()) && !moving.contains(event.getPlayer().getUniqueId())) cancel(event.getPlayer(), "trip-cancelled");
    }
}
