package at.esoren.vystormportals;

import Vystorm.vystorm_core.visual.ViewFrame;
import Vystorm.vystorm_core.visual.Point3;
import java.util.*;
import org.bukkit.World;
import org.bukkit.event.*;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;

/** Bounded asynchronous chunk requests followed by short-lived owner tickets. */
final class DestinationChunks implements AutoCloseable, Listener {
    private record Key(UUID world,int x,int z) {}
    private static final class Entry {
        long lastUse;
        final long requested;
        final boolean generate;
        boolean ticket, failed;
        Entry(long tick, boolean generate) { lastUse=tick; requested=tick; this.generate=generate; }
    }
    private final Plugin plugin;
    private final Map<Key,Entry> entries=new HashMap<>();
    private final Map<Key,Integer> leases=new HashMap<>();
    private volatile boolean closed;
    private final Vystorm.vystorm_core.compat.PlatformCompatInterface platform;
    /** @param platform Core's platform part, {@code api.compat().platform()} */
    DestinationChunks(Plugin plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface platform) {this.plugin=plugin;this.platform=platform;}
    void touch(ViewFrame frame,long tick) {
        touch(frame.world(), frame.origin(), tick, false);
    }
    void touch(UUID worldId, Point3 origin, long tick, boolean generate) {
        if(closed) return;
        World world=plugin.getServer().getWorld(worldId);if(world==null) return;
        int cx=(int)Math.floor(origin.x())>>4,cz=(int)Math.floor(origin.z())>>4;
        for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) {
            Key key=new Key(worldId,cx+dx,cz+dz);
            Entry previous=entries.get(key);
            if(previous!=null) {
                previous.lastUse=tick;
                if(!(previous.failed && tick-previous.requested>=200) && (!generate || previous.generate || previous.ticket)) continue;
                // Replace the entry so callbacks from its old, non-generating request
                // cannot acquire a ticket after the generating request supersedes it.
            }
            if(previous==null && entries.size()>=72) continue;
            Entry entry=new Entry(tick,generate);entries.put(key,entry);
            platform.getChunkAtAsync(world, key.x,key.z,generate).whenComplete((chunk,error)-> {
                if(closed || !plugin.isEnabled()) return;
                try {plugin.getServer().getScheduler().runTask(plugin,()-> {
                    if(closed || !plugin.isEnabled() || entries.get(key)!=entry) return;
                    if(error!=null || chunk==null || plugin.getServer().getWorld(worldId)!=world || !world.isChunkLoaded(key.x,key.z)) {
                        // Retain the entry until expiry to avoid a request storm on absent chunks.
                        entry.failed=true;
                        return;
                    }
                    hold(world,key.x,key.z);entry.ticket=true;
                });} catch(org.bukkit.plugin.IllegalPluginAccessException stopping) { /* no ticket was acquired */ }
            });
        }
    }
    void expire(long tick) {
        for(var item:List.copyOf(entries.entrySet())) if(tick-item.getValue().lastUse>200) {
            release(item.getKey(),item.getValue());entries.remove(item.getKey());
        }
    }
    private void release(Key key,Entry entry) {
        if(!entry.ticket) return;
        World world=plugin.getServer().getWorld(key.world);if(world!=null) release(world,key.x,key.z);
    }
    void hold(World world,int x,int z) {
        Key key=new Key(world.getUID(),x,z);
        if(!leases.containsKey(key))world.addPluginChunkTicket(x,z,plugin);
        leases.merge(key,1,Integer::sum);
    }
    void release(World world,int x,int z) {
        Key key=new Key(world.getUID(),x,z);Integer count=leases.get(key);
        if(count==null)return;
        if(count>1){leases.put(key,count-1);return;}
        leases.remove(key);world.removePluginChunkTicket(x,z,plugin);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void unloaded(WorldUnloadEvent event) {
        World world=event.getWorld();
        entries.keySet().removeIf(key->key.world.equals(world.getUID()));
        for(Key key:List.copyOf(leases.keySet())) if(key.world.equals(world.getUID())) {
            leases.remove(key);world.removePluginChunkTicket(key.x,key.z,plugin);
        }
    }
    @Override public void close() {
        closed=true;entries.clear();
        for(Key key:List.copyOf(leases.keySet())) {
            World world=plugin.getServer().getWorld(key.world);if(world!=null)world.removePluginChunkTicket(key.x,key.z,plugin);
        }
        leases.clear();
    }
}
