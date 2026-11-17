package at.esoren.vystormportals;

import Vystorm.vystorm_core.visual.ViewFrame;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** A manual portal. {@code trusted} players may use it while it is private (1.6.0); never null, sorted and immutable. */
public record Portal(String id, UUID owner, ViewFrame frame, String target, boolean publicAccess, int warmupTicks, Set<UUID> trusted, boolean paused) {
    /** Hard cap per portal, independent of the configurable limit. */
    static final int MAX_TRUSTED = 64;
    public Portal {
        Objects.requireNonNull(id); Objects.requireNonNull(owner); Objects.requireNonNull(frame);
        if (!id.matches("[a-z0-9_-]{1,40}")) throw new PortalInputException("invalid-name","Invalid portal name");
        if (target != null && !target.matches("[a-z0-9_-]{1,40}")) throw new IllegalArgumentException("Invalid target");
        if (id.equals(target)) throw new PortalInputException("self-link","A portal cannot target itself");
        if (warmupTicks < 0 || warmupTicks > 200) throw new PortalInputException("warmup","Warmup must be 0..200 ticks");
        trusted = trusted == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(trusted));
        if (trusted.size() > MAX_TRUSTED) throw new IllegalArgumentException("At most " + MAX_TRUSTED + " trusted players");
        if (trusted.contains(owner)) throw new IllegalArgumentException("The owner cannot be trusted on their own portal");
    }
    public Portal(String id, UUID owner, ViewFrame frame, String target, boolean publicAccess, int warmupTicks, Set<UUID> trusted) {
        this(id, owner, frame, target, publicAccess, warmupTicks, trusted, false);
    }
    public Portal(String id, UUID owner, ViewFrame frame, String target, boolean publicAccess, int warmupTicks) {
        this(id, owner, frame, target, publicAccess, warmupTicks, Set.of());
    }
    public Portal pause(boolean value) { return new Portal(id,owner,frame,target,publicAccess,warmupTicks,trusted,value); }
    /** Both endpoints must be open, including for owners and admins. */
    boolean routeOpen(Portal destination) { return destination!=null && !paused && !destination.paused(); }
    public Portal link(String target) { return new Portal(id,owner,frame,target,publicAccess,warmupTicks,trusted,paused); }
    public Portal rename(String newId) { return new Portal(newId,owner,frame,target,publicAccess,warmupTicks,trusted,paused); }
    public Portal access(boolean value) { return new Portal(id,owner,frame,target,value,warmupTicks,trusted,paused); }
    public Portal warmup(int ticks) { return new Portal(id,owner,frame,target,publicAccess,ticks,trusted,paused); }
    public Portal trust(UUID player) {
        Set<UUID> next = new TreeSet<>(trusted); next.add(player);
        return new Portal(id,owner,frame,target,publicAccess,warmupTicks,next,paused);
    }
    public Portal untrust(UUID player) {
        Set<UUID> next = new TreeSet<>(trusted); next.remove(player);
        return new Portal(id,owner,frame,target,publicAccess,warmupTicks,next,paused);
    }
}
